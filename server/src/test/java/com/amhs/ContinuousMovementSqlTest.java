package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Extending a movement that is already running. The failure this guards against is a second
 * extension going out while the first is unaccounted for, and a record of having told the
 * vehicle something that was never sent.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class ContinuousMovementSqlTest {
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment;
 @Autowired ContinuousMovement movement; @Autowired FleetState fleet;
 @Autowired TrafficControl traffic; @Autowired CommandOutbox outbox; @Autowired DeviceGateway gateway;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;

 // The active revision runs 101 -> 103 for OHT, with the source port at 101.
 private static final long SITE=1, REVISION=1, OHT=1, START=101, NEXT=102;
 private final String run=UUID.randomUUID().toString().substring(0,8);

 @BeforeEach void freshSite(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=TRUE WHERE site_id=? AND revision_id=?",SITE,REVISION);
  jdbc.update("UPDATE platform.site_operation SET more_enabled=TRUE,more_type='NODE',"
   +"more_remaining_nodes=1,node_count=2 WHERE site_id=?",SITE);
  report(START,true,true,true);
 }
 @AfterEach void restore(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND revision_id=?",SITE,REVISION);
  jdbc.update("UPDATE platform.site_operation SET more_remaining_nodes=3,node_count=20,more_type='NODE' WHERE site_id=?",SITE);
 }
 private void report(Long node,boolean online,boolean remote,boolean idle){
  tx.execute(s->fleet.report(SITE,REVISION,OHT,new VehicleReport(node,online,remote,idle,false,"SIMULATED")));
 }
 private long assignedOrder(String key){
  long id=tx.execute(s->orders.accept(SITE,new OrderRequest("TEST",run+"-"+key,"OHT-A","OHT-C",run+"-"+key,50),
   "tester","trace").order().id());
  tx.execute(s->assignment.assign(SITE,id,"tester","trace"));
  return id;
 }
 private ContinuousMovement.Extension extend(long orderId){ return tx.execute(s->movement.extend(SITE,orderId)); }
 private void acknowledgeAll(long orderId){
  // Acknowledgement only means something for a command that was actually attempted. The outbox
  // is shared and one sweep is capped, so publishing repeats until this order has been reached.
  for(int pass=0;pass<40&&outbox.byOrder(SITE,orderId).stream().anyMatch(c->c.status()==CommandStatus.PENDING);pass++)
   gateway.publishPending(SITE);
  for(var c:outbox.byOrder(SITE,orderId)) tx.execute(s->outbox.acknowledge(c.commandId()));
 }
 private List<Long> heldNodes(){ return traffic.heldBy(SITE,OHT).stream().map(NodeHold::nodeId).toList(); }

 @Test void aMovingVehicleNearTheEndOfItsAuthorityIsExtended(){
  long id=assignedOrder("extend");
  assertThat(heldNodes()).hasSize(2);
  // Moved one node on, so nothing authorized is left ahead: the configured threshold.
  report(NEXT,true,true,false);
  var extension=extend(id);
  assertThat(extension.decision()).isEqualTo("EXTENDED");
  assertThat(extension.addedNodeIds()).isNotEmpty();
  assertThat(extension.commandId()).isNotNull();
  var more=outbox.byOrder(SITE,id).stream().filter(c->c.commandType().equals("MORE")).toList();
  assertThat(more).hasSize(1);
  assertThat(more.getFirst().payload()).contains("\"addedNodeIds\"").contains("\"fromNodeId\"");
 }

 @Test void aSecondExtensionWaitsUntilTheFirstIsAccountedFor(){
  long id=assignedOrder("no-overlap");
  report(NEXT,true,true,false);
  assertThat(extend(id).decision()).isEqualTo("EXTENDED");
  // The device has said nothing about the first extension, so a second one is not produced.
  assertThat(extend(id).decision()).isEqualTo("MORE_OUTSTANDING");
  assertThat(outbox.byOrder(SITE,id).stream().filter(c->c.commandType().equals("MORE"))).hasSize(1);
  acknowledgeAll(id);
  // Once it is acknowledged the question is asked again on its merits, not blocked outright.
  assertThat(extend(id).decision()).isNotEqualTo("MORE_OUTSTANDING");
 }

 @Test void aStoppedVehicleIsNotExtended(){
  long id=assignedOrder("idle");
  report(START,true,true,true);
  // MORE lengthens a movement in progress; a stopped vehicle needs a movement command instead.
  assertThat(extend(id).decision()).isEqualTo("VEHICLE_NOT_MOVING");
  assertThat(outbox.byOrder(SITE,id).stream().filter(c->c.commandType().equals("MORE"))).isEmpty();
 }

 @Test void aVehicleNobodyCanLocateIsNotExtended(){
  long id=assignedOrder("lost");
  report(null,true,true,false);
  assertThat(extend(id).decision()).isEqualTo("POSITION_UNKNOWN");
 }

 @Test void anUncontrollableVehicleIsNotExtended(){
  long id=assignedOrder("offline");
  report(START,false,true,false);
  assertThat(extend(id).decision()).isEqualTo("VEHICLE_NOT_CONTROLLABLE");
 }

 @Test void authorityThatIsStillLongEnoughIsLeftAlone(){
  jdbc.update("UPDATE platform.site_operation SET more_remaining_nodes=1,node_count=4 WHERE site_id=?",SITE);
  long id=assignedOrder("not-needed");
  report(START,true,true,false);
  // Three nodes still ahead against a threshold of one.
  assertThat(extend(id).decision()).isEqualTo("NOT_NEEDED");
 }

 @Test void aVehicleThatAlreadyHoldsTheWholeRouteIsNotExtended(){
  jdbc.update("UPDATE platform.site_operation SET more_remaining_nodes=1,node_count=20 WHERE site_id=?",SITE);
  long id=assignedOrder("complete");
  report(START,true,true,false);
  assertThat(extend(id).decision()).isEqualTo("ROUTE_COMPLETE");
 }

 @Test void switchingOffMoreStopsExtensionsWithoutTouchingWhatIsHeld(){
  long id=assignedOrder("disabled");
  jdbc.update("UPDATE platform.site_operation SET more_enabled=FALSE WHERE site_id=?",SITE);
  report(START,true,true,false);
  assertThat(extend(id).decision()).isEqualTo("MORE_DISABLED");
  assertThat(heldNodes()).hasSize(2);
 }

 @Test void aFinishedOrderIsNotExtended(){
  long id=assignedOrder("finished");
  tx.execute(s->orders.cancel(SITE,id,"operator stopped it","tester","trace"));
  report(START,true,true,false);
  assertThat(extend(id).decision()).isEqualTo("ORDER_FINISHED");
 }

 @Test void blockedRoadYieldsNoExtensionRatherThanAnError(){
  long id=assignedOrder("blocked");
  // Somebody else takes the next node on the route before the extension is asked for.
  var route=jdbc.queryForObject("SELECT route_node_ids[3] FROM transport.assignment"
   +" WHERE order_id=? AND released_at IS NULL",Long.class,id);
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,1)",SITE,REVISION,route,9999);
  report(NEXT,true,true,false);
  var extension=extend(id);
  assertThat(extension.addedNodeIds()).isEmpty();
  assertThat(extension.decision()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
  assertThat(outbox.byOrder(SITE,id).stream().filter(c->c.commandType().equals("MORE"))).isEmpty();
 }

 @Test void aSweepOverTheSiteAnswersForEveryAssignedOrder(){
  long id=assignedOrder("sweep");
  report(NEXT,true,true,false);
  var all=tx.execute(s->movement.extendAll(SITE));
  assertThat(all).extracting(ContinuousMovement.Extension::orderId).contains(id);
 }
}
