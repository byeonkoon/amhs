package com.amhs;
import com.amhs.equipment.*;
import com.amhs.platform.ConfigurationException;
import com.amhs.topology.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * The two configuration surfaces that were still missing: the track's own parameters, and the
 * equipment the controller serves. They are tested together because what separates them is the
 * point — one reaches every vehicle, the other reaches one machine.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TopologyAndEquipmentSqlTest {
 @Autowired TopologyConfiguration topology; @Autowired EquipmentConfiguration equipment;
 @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, REVISION=1;
 private final String run=UUID.randomUUID().toString().substring(0,6);

 @BeforeEach void maintenance(){
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
 }
 @AfterEach void clean(){
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
 }
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }
 private ConfigurationRecordOf<NodeSettings> node(long id){
  var r=topology.nodes(SITE,REVISION).stream().filter(n->n.id()==id).findFirst().orElseThrow();
  return new ConfigurationRecordOf<>(r.id(),r.version(),r.settings());
 }
 private record ConfigurationRecordOf<T>(long id,long version,T settings){}

 @Test void aNodeInUseCannotBeTakenOutOfService(){
  var before=node(101);
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,1)",SITE,REVISION,101,9001);
  // A vehicle is permitted to stand there, so closing it would route somebody else into it.
  rejected(()->topology.updateNode(SITE,REVISION,101,before.version(),
   new NodeSettings(before.settings().code(),before.settings().xMm(),before.settings().yMm(),false,before.settings().stopNode(),before.settings().mergePoint())),
   409,"NODE_IN_USE");
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=? AND node_id=?",SITE,101);
  var closed=topology.updateNode(SITE,REVISION,101,before.version(),
   new NodeSettings(before.settings().code(),before.settings().xMm(),before.settings().yMm(),false,before.settings().stopNode(),before.settings().mergePoint()));
  assertThat(closed.settings().enabled()).isFalse();
  topology.updateNode(SITE,REVISION,101,closed.version(),
   new NodeSettings(before.settings().code(),before.settings().xMm(),before.settings().yMm(),true,before.settings().stopNode(),before.settings().mergePoint()));
 }

 @Test void aSegmentWhoseEndIsHeldCannotBeClosedAndItsEndsCannotMove(){
  var segment=topology.edges(SITE,REVISION).stream().filter(e->e.settings().fromNodeId()==101).findFirst().orElseThrow();
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,1)",SITE,REVISION,101,9002);
  var s=segment.settings();
  rejected(()->topology.updateEdge(SITE,REVISION,segment.id(),segment.version(),
   new EdgeSettings(s.fromNodeId(),s.toNodeId(),s.cost(),false)),409,"SEGMENT_IN_USE");
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=? AND node_id=?",SITE,101);
  // Endpoints are what the segment is; changing them is a different segment, not a changed one.
  rejected(()->topology.updateEdge(SITE,REVISION,segment.id(),segment.version(),
   new EdgeSettings(s.fromNodeId(),s.toNodeId()+1,s.cost(),true)),422,"EDGE_ENDPOINTS_FIXED");
  rejected(()->topology.updateEdge(SITE,REVISION,segment.id(),segment.version(),
   new EdgeSettings(s.fromNodeId(),s.toNodeId(),-1,true)),422,"INVALID_COST");
  var tuned=topology.updateEdge(SITE,REVISION,segment.id(),segment.version(),
   new EdgeSettings(s.fromNodeId(),s.toNodeId(),s.cost()+500,true));
  assertThat(tuned.settings().cost()).isEqualTo(s.cost()+500);
  topology.updateEdge(SITE,REVISION,segment.id(),tuned.version(),s);
 }

 @Test void twoPeopleChangingTheSameNodeDoNotSilentlyMerge(){
  var before=node(102);
  var s=before.settings();
  topology.updateNode(SITE,REVISION,102,before.version(),new NodeSettings(s.code(),s.xMm(),s.yMm()+10,s.enabled(),s.stopNode(),s.mergePoint()));
  rejected(()->topology.updateNode(SITE,REVISION,102,before.version(),
   new NodeSettings(s.code(),s.xMm(),s.yMm()+20,s.enabled(),s.stopNode(),s.mergePoint())),409,"VERSION_CONFLICT");
 }

 @Test void aRacksCellsAreGeneratedFromItsSizeAndKeepWhatWasDecidedAboutThem(){
  var unit=equipment.createUnit(SITE,new EquipmentSettings(REVISION,run+"-STK",run+" 스토커","STOCKER",true));
  var rack=equipment.createRack(SITE,unit.id(),new RackSettings("R1",2,3,true));
  assertThat(equipment.slots(SITE,unit.id(),rack.id())).hasSize(6);

  var cell=equipment.slots(SITE,unit.id(),rack.id()).getFirst();
  // Marking a cell unusable is configuration, and a reason is required so the mark says why.
  rejected(()->equipment.setSlotUsable(SITE,unit.id(),rack.id(),cell.id(),false,"  "),422,"UNUSABLE_REASON_REQUIRED");
  var blocked=equipment.setSlotUsable(SITE,unit.id(),rack.id(),cell.id(),false,"레일 간섭");
  assertThat(blocked.usable()).isFalse();
  assertThat(blocked.unusableReason()).isEqualTo("레일 간섭");

  // Growing fills in the new cells and leaves the decision on the existing one alone.
  var grown=equipment.updateRack(SITE,unit.id(),rack.id(),rack.version(),new RackSettings("R1",3,4,true));
  assertThat(equipment.slots(SITE,unit.id(),rack.id())).hasSize(12);
  assertThat(equipment.slots(SITE,unit.id(),rack.id()).stream().filter(x->!x.usable())).hasSize(1);

  // Shrinking past a marked cell would drop the reason it was being kept empty.
  var marked=equipment.slots(SITE,unit.id(),rack.id()).stream().filter(x->!x.usable()).findFirst().orElseThrow();
  equipment.setSlotUsable(SITE,unit.id(),rack.id(),marked.id(),true,null);
  equipment.setSlotUsable(SITE,unit.id(),rack.id(),
   equipment.slots(SITE,unit.id(),rack.id()).getLast().id(),false,"청소 중");
  rejected(()->equipment.updateRack(SITE,unit.id(),rack.id(),grown.version(),new RackSettings("R1",2,3,true)),
   409,"UNUSABLE_SLOT_WOULD_BE_REMOVED");
 }

 @Test void equipmentKindAndRevisionAreChecked(){
  rejected(()->equipment.createUnit(SITE,new EquipmentSettings(REVISION,run+"-BAD","x","CONVEYOR",true)),
   422,"INVALID_EQUIPMENT_KIND");
  rejected(()->equipment.createUnit(SITE,new EquipmentSettings(9999,run+"-REV","x","BUFFER",true)),
   422,"REVISION_NOT_FOUND");
 }

 @Test void configurationStillNeedsMaintenance(){
  jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=?",SITE);
  var before=node(103);
  var s=before.settings();
  rejected(()->topology.updateNode(SITE,REVISION,103,before.version(),
   new NodeSettings(s.code(),s.xMm(),s.yMm(),s.enabled(),s.stopNode(),s.mergePoint())),409,"SITE_NOT_IN_MAINTENANCE");
  rejected(()->equipment.createUnit(SITE,new EquipmentSettings(REVISION,run+"-RUN","x","BUFFER",true)),
   409,"SITE_NOT_IN_MAINTENANCE");
 }
}
