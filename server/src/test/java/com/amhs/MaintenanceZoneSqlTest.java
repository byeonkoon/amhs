package com.amhs;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.OrderRequest;
import com.amhs.transport.TransportAssignment;
import com.amhs.transport.TransportOrders;
import com.amhs.transport.TransportRejected;
import com.amhs.transport.ZoneEvacuation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Somewhere off the line to put a vehicle that should not be on it, and a way to say so.
 *
 * <p>Two things have to hold or the pair is useless. A vehicle put in the maintenance area stays
 * there -- the rule that moves idle vehicles out of zones must not apply, or it would put the
 * vehicle straight back on the line it was just taken off. And a vehicle taken out of service stops
 * being given work -- a vehicle somebody decided is not fit to run, that the next dispatch picks up
 * anyway, was never taken out of anything.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MaintenanceZoneSqlTest {
 @Autowired FleetState fleet; @Autowired TrafficControl traffic; @Autowired ZoneEvacuation evacuation;
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT=1;
 /** A node of the maintenance siding, which the map seeds off the ring. */
 private long maintenanceNode;

 @BeforeEach void findTheSiding(){
  maintenanceNode=jdbc.queryForObject(
   "SELECT zn.node_id FROM topology.zone_node zn JOIN topology.zone z ON z.id=zn.zone_id"
   +" WHERE z.site_id=? AND z.revision_id=? AND z.kind='MAINTENANCE' ORDER BY zn.node_id LIMIT 1",
   Long.class,SITE,REVISION);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?) WHERE site_id=?",OHT,SITE);
 }
 @AfterEach void leaveItAsItWas(){
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(101L,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }
 private void standsAt(long nodeId){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(nodeId,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
 }

 @Test void theMaintenanceAreaIsOffTheRunningLineAndSaysWhichKindItIs(){
  var zones=traffic.zones(SITE);
  assertThat(zones).anySatisfy(z->{
   assertThat(z.forMaintenance()).isTrue();
   assertThat(z.enabled()).isTrue();
   assertThat(z.nodeIds()).as("somewhere to put a vehicle, with room for more than one").hasSizeGreaterThan(1);
  });
  // The traffic zones are still what they were: adding a kind did not reclassify them.
  assertThat(zones).anySatisfy(z->assertThat(z.forMaintenance()).isFalse());
 }

 /**
  * The rule that empties zones must not empty this one.
  *
  * <p>A vehicle is put here because it should not be on the line. Moving it out when it goes idle
  * would undo the only thing anyone wanted, and it would happen by itself, on the next pass.
  */
 @Test void aVehicleLeftInTheMaintenanceAreaIsNotMovedOutOfIt(){
  standsAt(maintenanceNode);
  var steps=tx.execute(s->evacuation.evacuateAll(SITE));
  assertThat(steps).as("nothing to do about a vehicle that is where it should be")
   .noneSatisfy(step->assertThat(step.vehicleId()).isEqualTo(OHT));
  assertThat(fleet.find(SITE,REVISION,OHT).orElseThrow().nodeId()).isEqualTo(maintenanceNode);
 }

 /** Taking it out of service has to say why, and carries who said so. */
 @Test void takingAVehicleOutOfServiceRecordsWhyAndWho(){
  standsAt(101L);
  var out=tx.execute(s->fleet.takeOutOfService(SITE,REVISION,OHT,"집전 이상 점검","mgr1"));
  assertThat(out.outOfService()).isTrue();
  assertThat(out.outOfServiceReason()).isEqualTo("집전 이상 점검");
  assertThat(out.outOfServiceBy()).isEqualTo("mgr1");
  assertThat(out.outOfServiceSince()).isNotNull();

  assertThatThrownBy(()->tx.executeWithoutResult(s->fleet.takeOutOfService(SITE,REVISION,OHT,"  ","mgr1")))
   .isInstanceOf(IllegalArgumentException.class).hasMessage("OUT_OF_SERVICE_REASON_REQUIRED");

  var back=tx.execute(s->fleet.returnToService(SITE,REVISION,OHT));
  assertThat(back.outOfService()).isFalse();
  assertThat(back.outOfServiceReason()).isNull();
 }

 /**
  * A vehicle taken out of service is not given work.
  *
  * <p>Checked through dispatch rather than at the screen: a screen that hides the button while the
  * dispatcher still picks the vehicle up has not taken it out of anything.
  */
 @Test void aVehicleOutOfServiceIsNotGivenWork(){
  standsAt(101L);
  long orderId=tx.execute(s->orders.accept(SITE,
   new OrderRequest("TEST",UUID.randomUUID().toString(),"OHT-A","OHT-C",UUID.randomUUID().toString(),50),
   "test",null).order().id());
  tx.executeWithoutResult(s->fleet.takeOutOfService(SITE,REVISION,OHT,"집전 이상 점검","mgr1"));

  // And the reason given is the true one: there is no vehicle to be had, not that one changed
  // under the lock. A dispatcher that answers with a race nobody had sends people looking for it.
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,orderId,"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,
    e->assertThat(e.code()).isEqualTo("NO_AVAILABLE_VEHICLE"));

  // Put back, and the same order finds the same vehicle.
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));
  assertThat(tx.execute(s->assignment.assign(SITE,orderId,"test",null)).assignment().vehicleId()).isEqualTo(OHT);
 }
}
