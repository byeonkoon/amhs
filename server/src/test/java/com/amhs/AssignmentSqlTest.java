package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Assignment holds a vehicle, records why, and emits the movement command in the same breath.
 * The cases below are the ones where doing any of those separately would go wrong.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class AssignmentSqlTest {
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment;
 @Autowired FleetState fleet; @Autowired CommandOutbox outbox; @Autowired TrafficControl traffic;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;

 private static final long SITE=1, REVISION=1, OHT=1, AGV=2, OHT_SOURCE_NODE=101;
 private final String run=UUID.randomUUID().toString().substring(0,8);

 private long order(String key){
  return tx.execute(s->orders.accept(SITE,new OrderRequest("TEST",run+"-"+key,"OHT-A","OHT-B",run+"-"+key,50),
   "tester","trace").order().id());
 }
 private void report(long vehicleId,Long node,boolean online,boolean remote,boolean idle){
  tx.execute(s->fleet.report(SITE,REVISION,vehicleId,new VehicleReport(node,online,remote,idle,false,"SIMULATED")));
 }
 private void parked(){
  // The seeded profiles have assignment switched off because the seed is configuration only.
  // Assignment is a configuration decision, not a reported state, so it is turned on explicitly.
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=TRUE WHERE site_id=? AND revision_id=?",SITE,REVISION);
  // Both vehicles then start reported but unusable, so each test turns on only what it needs.
  report(OHT,null,false,false,false); report(AGV,null,false,false,false);
 }
 @org.junit.jupiter.api.AfterEach void restoreSeededConfiguration(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND revision_id=?",SITE,REVISION);
 }
 private void available(long vehicleId,long node){ report(vehicleId,node,true,true,true); }
 private void releaseAll(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
 }
 private TransportAssignment.Assigned assign(long orderId){
  return tx.execute(s->assignment.assign(SITE,orderId,"tester","trace"));
 }

 @Test void aConfiguredVehicleThatHasNeverReportedIsNotAssignable(){
  releaseAll(); parked();
  long id=order("never-reported");
  assertThatThrownBy(()->assign(id)).isInstanceOf(TransportRejected.class)
   .hasFieldOrPropertyWithValue("code","NO_AVAILABLE_VEHICLE");
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);
 }

 @Test void aVehicleWhoseWhereaboutsAreUnknownIsReportedAsSuchRatherThanPlaced(){
  releaseAll(); parked();
  // Online and ready, but nothing has said where it is: that is not the same as unreachable.
  report(OHT,null,true,true,true);
  long id=order("position-unknown");
  assertThatThrownBy(()->assign(id)).isInstanceOf(TransportRejected.class)
   .hasFieldOrPropertyWithValue("code","APPROACH_ROUTE_START_UNKNOWN");
 }

 @Test void assigningWritesTheHoldTheStateAndTheCommandTogether(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  long id=order("assign-ok");
  var assigned=assign(id);
  assertThat(assigned.created()).isTrue();
  assertThat(assigned.order().status()).isEqualTo(OrderStatus.ASSIGNED);
  assertThat(assigned.assignment().vehicleId()).isEqualTo(OHT);
  assertThat(assigned.assignment().releasedAt()).isNull();
  var commands=outbox.byOrder(SITE,id);
  assertThat(commands).hasSize(1);
  assertThat(commands.getFirst().commandType()).isEqualTo("MOVE");
  assertThat(commands.getFirst().status()).isEqualTo(CommandStatus.PENDING);
  assertThat(commands.getFirst().payload()).contains("\"deliveryEdgeIds\"").contains("\"vehicleCode\"");
  assertThat(orders.events(SITE,id)).extracting(OrderEventView::newStatus).contains(OrderStatus.ASSIGNED);
 }

 @Test void repeatingTheRequestReturnsTheVehicleAlreadyHeldAndNoSecondCommand(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  long id=order("assign-twice");
  var first=assign(id); var again=assign(id);
  assertThat(again.created()).isFalse();
  assertThat(again.assignment().id()).isEqualTo(first.assignment().id());
  assertThat(outbox.byOrder(SITE,id)).hasSize(1);
 }

 @Test void aHeldVehicleIsNotOfferedToTheNextOrder(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  long first=order("holder"); assign(first);
  long second=order("waiter");
  // The only usable vehicle is busy, so the second order waits instead of taking it.
  assertThatThrownBy(()->assign(second)).isInstanceOf(TransportRejected.class)
   .hasFieldOrPropertyWithValue("code","NO_AVAILABLE_VEHICLE");
  assertThat(outbox.byOrder(SITE,second)).isEmpty();
  assertThat(orders.find(SITE,second).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);
 }

 @Test void finishingAnOrderReleasesItsVehicleForTheNextOne(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  long first=order("release-holder"); assign(first);
  tx.execute(s->orders.cancel(SITE,first,"operator stopped it","tester","trace"));
  assertThat(orders.find(SITE,first).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
  long second=order("release-waiter");
  assertThat(assign(second).assignment().vehicleId()).isEqualTo(OHT);
 }

 @Test void aPositionOutsideTheRevisionIsRejectedAsABadReport(){
  assertThatThrownBy(()->report(OHT,999999L,true,true,true))
   .isInstanceOf(IllegalArgumentException.class).hasMessage("NODE_NOT_IN_REVISION");
  assertThatThrownBy(()->report(999999L,OHT_SOURCE_NODE,true,true,true))
   .isInstanceOf(IllegalArgumentException.class).hasMessage("VEHICLE_NOT_FOUND");
 }

 @Test void theCommandCarriesOnlyWhatTrafficPermitted(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  long id=order("authorized-run");
  assign(id);
  var payload=outbox.byOrder(SITE,id).getFirst().payload();
  assertThat(payload).contains("\"authorizedNodeIds\"").contains("\"authorityComplete\"");
  assertThat(traffic.heldBy(SITE,OHT)).isNotEmpty();
  // Permission starts where the vehicle is standing, not somewhere along the plan.
  assertThat(traffic.heldBy(SITE,OHT).getFirst().nodeId()).isEqualTo(OHT_SOURCE_NODE);
 }

 @Test void aVehicleThatCannotBeLetOutIsStillAssignedButNotCommanded(){
  releaseAll(); parked(); available(OHT,OHT_SOURCE_NODE);
  // Somebody else is standing exactly where this vehicle is supposed to start.
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence)"
   +" VALUES(?,?,?,?,1)",SITE,REVISION,OHT_SOURCE_NODE,9999);
  long id=order("stalled-start");
  var assigned=assign(id);
  assertThat(assigned.order().status()).isEqualTo(OrderStatus.ASSIGNED);
  assertThat(assigned.commandId()).isNull();
  // Holding the vehicle is not permission to move it, so no movement command exists yet.
  assertThat(outbox.byOrder(SITE,id)).isEmpty();
 }

 @Test void aReportRecordsWhereItCameFromSoSimulationIsNotMistakenForEvidence(){
  releaseAll(); parked();
  var snapshot=tx.execute(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(OHT_SOURCE_NODE,true,true,true,false,"SIMULATED")));
  assertThat(snapshot.source()).isEqualTo("SIMULATED");
  assertThat(snapshot.nodeId()).isEqualTo(OHT_SOURCE_NODE);
 }
}
