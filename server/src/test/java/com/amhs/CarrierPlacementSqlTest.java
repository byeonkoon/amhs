package com.amhs;
import com.amhs.equipment.EquipmentConfiguration;
import com.amhs.equipment.EquipmentSettings;
import com.amhs.equipment.RackSettings;
import com.amhs.inventory.CarrierPlacements;
import com.amhs.platform.ConfigurationException;
import com.amhs.transport.OrderRequest;
import com.amhs.transport.TransportOrders;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * What is stored, and where -- including the places where "where" is only partly known.
 *
 * <p>The cases that matter are the ones where getting it wrong makes the record read as more
 * certain than it is: a cell somebody is keeping empty filled anyway, two carriers in one cell,
 * a carrier recorded as sitting still while an order is carrying it, and a placement with no
 * account of how anybody knows.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class CarrierPlacementSqlTest {
 @Autowired CarrierPlacements placements; @Autowired EquipmentConfiguration equipment;
 @Autowired TransportOrders orders;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1;
 private final String run=UUID.randomUUID().toString().substring(0,6);
 private long unitId; private long rackId;
 /** The site's mode as this class found it. Other tests share the database and expect theirs back. */
 private String modeBefore;

 @BeforeEach void aRackOfItsOwn(){
  modeBefore=jdbc.queryForObject("SELECT mode FROM platform.site_operation WHERE site_id=?",String.class,SITE);
  mode("MAINTENANCE");
  unitId=equipment.createUnit(SITE,new EquipmentSettings(REVISION,run+"-STK",run+" 스토커","STOCKER",true)).id();
  rackId=equipment.createRack(SITE,unitId,new RackSettings(run+"-R1",2,3,true)).id();
  // Running, on purpose: recording what is in the equipment must work while the site works.
  mode("RUNNING");
 }
 @AfterEach void clean(){
  jdbc.update("DELETE FROM inventory.placement WHERE carrier_code LIKE ?",run+"%");
  jdbc.update("DELETE FROM equipment.slot WHERE rack_id IN (SELECT id FROM equipment.rack WHERE unit_id=?)",unitId);
  jdbc.update("DELETE FROM equipment.rack WHERE unit_id=?",unitId);
  jdbc.update("DELETE FROM equipment.unit WHERE id=?",unitId);
  mode(modeBefore);
 }
 /** Configuring equipment needs maintenance; recording what is in it deliberately does not. */
 private void mode(String value){
  if(value!=null) jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=?",value,SITE);
 }
 private long cell(int level,int col){
  return placements.cells(SITE,unitId,rackId).stream()
   .filter(c->c.level()==level&&c.col()==col).findFirst().orElseThrow().slotId();
 }
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }

 @Test void aCarrierIsRecordedIntoACellAndTheCellThenSaysSo(){
  var placed=placements.placeInCell(SITE,run+"-C1",unitId,rackId,cell(1,1),"현장 확인","mgr1");
  assertThat(placed.carrierCode()).isEqualTo(run+"-C1");
  assertThat(placed.inACell()).isTrue();
  assertThat(placed.reason()).isEqualTo("현장 확인");
  assertThat(placed.actorCode()).isEqualTo("mgr1");

  var cells=placements.cells(SITE,unitId,rackId);
  assertThat(cells).hasSize(6);
  assertThat(cells).filteredOn(c->c.level()==1&&c.col()==1).singleElement().satisfies(c->{
   assertThat(c.carrierCode()).isEqualTo(run+"-C1");
   assertThat(c.occupied()).isTrue();
   assertThat(c.usable()).as("holding something is not the same as being blocked").isTrue();
  });
  assertThat(cells).filteredOn(c->!(c.level()==1&&c.col()==1)).allSatisfy(c->
   assertThat(c.occupied()).isFalse());
 }

 /** Recording it somewhere else moves it. A carrier is in one place, so the old row is not left. */
 @Test void recordingItElsewhereMovesItRatherThanDuplicatingIt(){
  placements.placeInCell(SITE,run+"-C2",unitId,rackId,cell(1,1),"현장 확인","mgr1");
  placements.placeInCell(SITE,run+"-C2",unitId,rackId,cell(2,3),"옮긴 것 확인","mgr1");
  assertThat(placements.placements(SITE)).filteredOn(p->p.carrierCode().equals(run+"-C2")).hasSize(1);
  var cells=placements.cells(SITE,unitId,rackId);
  assertThat(cells).filteredOn(c->c.level()==1&&c.col()==1).singleElement()
   .satisfies(c->assertThat(c.occupied()).as("the cell it left is free").isFalse());
  assertThat(cells).filteredOn(c->c.level()==2&&c.col()==3).singleElement()
   .satisfies(c->assertThat(c.carrierCode()).isEqualTo(run+"-C2"));
 }

 @Test void twoCarriersCannotBeRecordedIntoOneCell(){
  long target=cell(1,2);
  placements.placeInCell(SITE,run+"-C3",unitId,rackId,target,"현장 확인","mgr1");
  rejected(()->placements.placeInCell(SITE,run+"-C4",unitId,rackId,target,"현장 확인","mgr2"),409,"CELL_OCCUPIED");
 }

 /**
  * A cell somebody is keeping empty stays empty. Filling it while the mark stays in place would
  * make the mark mean nothing, and the reason it was being kept empty is the part that matters.
  */
 @Test void aCellMarkedNotToBeFilledIsNotFilled(){
  mode("MAINTENANCE");
  long blocked=cell(2,1);
  equipment.setSlotUsable(SITE,unitId,rackId,blocked,false,"레일 손상");
  mode("RUNNING");
  rejected(()->placements.placeInCell(SITE,run+"-C5",unitId,rackId,blocked,"현장 확인","mgr1"),409,"CELL_NOT_USABLE");

  // And the two facts stay separate: the cell reports the mark and its reason, and is empty.
  assertThat(placements.cells(SITE,unitId,rackId)).filteredOn(c->c.slotId()==blocked).singleElement()
   .satisfies(c->{
    assertThat(c.usable()).isFalse();
    assertThat(c.unusableReason()).isEqualTo("레일 손상");
    assertThat(c.occupied()).isFalse();
   });
 }

 /** A carrier handed over at a port with nobody recording the cell is its own answer. */
 @Test void aPortHandoverIsRecordedAsNotKnowingTheCell(){
  long portId=jdbc.queryForObject("SELECT id FROM topology.port WHERE site_id=? AND revision_id=? AND code='OHT-A'",Long.class,SITE,REVISION);
  var at=placements.recordAtPort(SITE,run+"-C6",portId,"설비가 받아 갔음","mgr1");
  assertThat(at.inACell()).isFalse();
  assertThat(at.portId()).isEqualTo(portId);
  // It is not in any cell, so no cell claims it; that is different from it being lost.
  assertThat(placements.cells(SITE,unitId,rackId)).allSatisfy(c->assertThat(c.occupied()).isFalse());
  assertThat(placements.placements(SITE)).anySatisfy(p->{
   assertThat(p.carrierCode()).isEqualTo(run+"-C6");
   assertThat(p.slotId()).isNull();
  });
 }

 @Test void aPlacementHasToSayHowAnybodyKnows(){
  rejected(()->placements.placeInCell(SITE,run+"-C7",unitId,rackId,cell(1,3),"  ","mgr1"),422,"PLACEMENT_REASON_REQUIRED");
  rejected(()->placements.placeInCell(SITE,run+"-C7",unitId,rackId,cell(1,3),null,"mgr1"),422,"PLACEMENT_REASON_REQUIRED");
  rejected(()->placements.placeInCell(SITE,"  ",unitId,rackId,cell(1,3),"현장 확인","mgr1"),422,"CARRIER_CODE_REQUIRED");
 }

 @Test void aCellOfAnotherRackIsNotFound(){
  rejected(()->placements.placeInCell(SITE,run+"-C8",unitId,rackId,999_999_999L,"현장 확인","mgr1"),404,"CELL_NOT_FOUND");
 }

 /** Removing forgets where it is, because it is not here; what happened stays in the operation record. */
 @Test void removingForgetsThePlaceAndSaysSoWhenThereIsNothingToForget(){
  placements.placeInCell(SITE,run+"-C9",unitId,rackId,cell(1,1),"현장 확인","mgr1");
  placements.remove(SITE,run+"-C9","출고","mgr1");
  assertThat(placements.placements(SITE)).noneSatisfy(p->assertThat(p.carrierCode()).isEqualTo(run+"-C9"));
  rejected(()->placements.remove(SITE,run+"-C9","출고","mgr1"),404,"PLACEMENT_NOT_FOUND");
 }

 /** The counts at the top of the screen are read the same way the lists under them are. */
 @Test void theSummaryCountsStoredCellsAndWhatIsOnlyKnownToAPort(){
  long portId=jdbc.queryForObject("SELECT id FROM topology.port WHERE site_id=? AND revision_id=? AND code='OHT-A'",Long.class,SITE,REVISION);
  var before=placements.summary(SITE);
  placements.placeInCell(SITE,run+"-D1",unitId,rackId,cell(1,1),"현장 확인","mgr1");
  placements.placeInCell(SITE,run+"-D2",unitId,rackId,cell(1,2),"현장 확인","mgr1");
  placements.recordAtPort(SITE,run+"-D3",portId,"설비가 받아 갔음","mgr1");
  var after=placements.summary(SITE);
  assertThat(after.stored()-before.stored()).isEqualTo(3);
  assertThat(after.inCells()-before.inCells()).isEqualTo(2);
  assertThat(after.atPortOnly()-before.atPortOnly()).isEqualTo(1);
  // The rack of this run is 2 by 3, and its cells are counted as cells rather than as placements.
  assertThat(after.cells()).as("every configured cell is counted").isGreaterThanOrEqualTo(6);
 }

 /**
  * A carrier an unfinished order is carrying is not recorded as sitting still.
  *
  * <p>Two answers about where one carrier is, is worse than one answer that is merely incomplete:
  * the order is the thing that is moving it, and a cell recorded underneath that would be read as
  * current. The check also takes the carrier's claim, so an order accepted in the same moment
  * cannot slip between reading and writing.
  */
 @Test void aCarrierAnOrderIsCarryingIsNotRecordedAsStored(){
  String carrier=run+"-E1";
  long orderId=tx.execute(s->orders.accept(SITE,
   new OrderRequest("TEST",UUID.randomUUID().toString(),"OHT-A","OHT-C",carrier,50),"test",null).order().id());
  try{
   rejected(()->placements.placeInCell(SITE,carrier,unitId,rackId,cell(1,1),"현장 확인","mgr1"),
    409,"CARRIER_IN_TRANSPORT");
   long portId=jdbc.queryForObject("SELECT id FROM topology.port WHERE site_id=? AND revision_id=? AND code='OHT-A'",Long.class,SITE,REVISION);
   rejected(()->placements.recordAtPort(SITE,carrier,portId,"설비가 받아 갔음","mgr1"),
    409,"CARRIER_IN_TRANSPORT");
  } finally {
   jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE id=?",orderId);
  }
  // Once no order is carrying it, the same record is accepted.
  var placed=placements.placeInCell(SITE,carrier,unitId,rackId,cell(1,1),"현장 확인","mgr1");
  assertThat(placed.inACell()).isTrue();
 }
}
