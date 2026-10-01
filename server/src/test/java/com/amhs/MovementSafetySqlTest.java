package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The boundaries between stopping work and moving vehicles: a cancelled or recovering order gets no
 * more road, a command that may already be driving a vehicle keeps its road until someone confirms,
 * the first movement ends at the pickup, a state nobody has confirmed lately is not dispatched on,
 * and a vehicle standing somewhere blocks it whether or not it was given it.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MovementSafetySqlTest {
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment;
 @Autowired ContinuousMovement movement; @Autowired FleetState fleet;
 @Autowired TrafficControl traffic; @Autowired CommandOutbox outbox;
 @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;

 // Active layout: a one-way OHT ring 101 -> 102 -> ... -> 120 -> 101. OHT-A sits at 101, OHT-C at 105.
 private static final long SITE=1, REVISION=1, OHT=1, OTHER=2, SOURCE=101;

 @BeforeEach void prepare(){
  // Earlier cases leave assignments, holds and commands behind; none of them is this case's.
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id<>?",SITE,OHT);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?) WHERE site_id=?",OHT,SITE);
  jdbc.update("UPDATE platform.site_operation SET more_enabled=TRUE,more_type='NODE',more_remaining_nodes=1,"
   +"more_min_remaining_nodes=0,node_count=20,more_vehicle_signal=FALSE,merge_control='CONTROLLER' WHERE site_id=?",SITE);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE WHERE site_id=? AND revision_id=?",SITE,REVISION);
  standAt(SOURCE,true);
 }

 private void standAt(long node,boolean idle){
  fleet.report(SITE,REVISION,OHT,new VehicleReport(node,true,true,idle,false,"SIMULATED"));
 }
 private void arrive(long node,boolean idle){ standAt(node,idle); traffic.advanced(SITE,OHT,node); }
 private long order(String to){
  return orders.accept(SITE,new OrderRequest("SAFETY",UUID.randomUUID().toString(),"OHT-A",to,"C",50),"test",null).order().id();
 }
 private long assigned(String to){ long id=order(to); assignment.assign(SITE,id,"test",null); return id; }
 private void advance(long id,OrderStatus... path){ for(var s:path) orders.advance(SITE,id,s,"test","test",null); }
 /** What a publisher and the device would do: take the order's commands out and acknowledge them. */
 private void acknowledge(long id){
  outbox.claim(SITE,500,20);
  for(var c:outbox.byOrder(SITE,id))
   if(c.status()==CommandStatus.DISPATCHING||c.status()==CommandStatus.SENT||c.status()==CommandStatus.FAILED)
    outbox.acknowledge(c.commandId());
 }
 private List<Long> held(long vehicle){ return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList(); }
 private List<Long> authorized(DeviceCommand command) throws Exception {
  var out=new ArrayList<Long>();
  json.readTree(command.payload()).get("authorizedNodeIds").forEach(n->out.add(n.asLong()));
  return out;
 }

 // ---- cancel against commands that may already be out (R1) ----

 @Test void aCancelBeforeAnythingLeftTakesTheMoveBackAndEndsTheOrder(){
  long id=assigned("OHT-C");
  assertThat(outbox.byOrder(SITE,id)).extracting(DeviceCommand::status).containsOnly(CommandStatus.PENDING);
  var cancelled=orders.cancel(SITE,id,"no longer needed","test",null);
  assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
  assertThat(outbox.byOrder(SITE,id)).extracting(DeviceCommand::status).containsOnly(CommandStatus.WITHDRAWN);
  assertThat(outbox.claim(SITE,500,20)).noneMatch(c->c.orderId()==id);
  assertThat(assignment.active(SITE)).noneMatch(a->a.orderId()==id);
 }

 @Test void aCancelAfterTheMoveLeftWaitsForConfirmationAndKeepsTheRoad(){
  standAt(119,true);
  long id=assigned("OHT-C");
  assertThat(held(OHT)).containsExactly(119L,120L,SOURCE);
  // The publisher has the MOVE: the vehicle may be driving on it from here on.
  assertThat(outbox.claim(SITE,500,20)).anyMatch(c->c.orderId()==id);
  var requested=orders.cancel(SITE,id,"no longer needed","test",null);
  assertThat(requested.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
  assertThat(assignment.active(SITE)).anyMatch(a->a.orderId()==id);
  assertThat(held(OHT)).containsExactly(119L,120L,SOURCE);
  // Nothing was ever picked up, so the confirmation ends the order instead of opening a recovery.
  var confirmed=orders.advance(SITE,id,OrderStatus.CANCELLED,"vehicle stopped","test",null);
  assertThat(confirmed.status()).isEqualTo(OrderStatus.CANCELLED);
  assertThat(held(OHT)).containsExactly(119L);
 }

 // ---- leaving recovery (R2) ----

 @Test void aRepeatedCancelConfirmationDoesNotEndARecovery(){
  long id=order("OHT-C");
  advance(id,OrderStatus.ASSIGNED,OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.CANCEL_REQUESTED);
  assertThat(orders.advance(SITE,id,OrderStatus.CANCELLED,"ack","test",null).status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  int events=orders.events(SITE,id).size();
  var again=orders.advance(SITE,id,OrderStatus.CANCELLED,"ack","test",null);
  assertThat(again.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(again.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(orders.events(SITE,id)).hasSize(events);
  // Nor does any other plain transition while nobody has said where the carrier is.
  assertThatThrownBy(()->orders.advance(SITE,id,OrderStatus.COMPLETED,"done","test",null))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("RECOVERY_EVIDENCE_REQUIRED"));
  // Still on the vehicle is not an answer, and an answer needs to say how it was established.
  assertThatThrownBy(()->orders.resolveRecovery(SITE,id,CarrierLocation.VEHICLE,"seen","test",null))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("RECOVERY_LOCATION_REQUIRED"));
  assertThatThrownBy(()->orders.resolveRecovery(SITE,id,CarrierLocation.DESTINATION," ","test",null))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("RECOVERY_REASON_REQUIRED"));
  var resolved=orders.resolveRecovery(SITE,id,CarrierLocation.DESTINATION,"operator saw it on the port","test",null);
  assertThat(resolved.status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(resolved.carrierLocation()).isEqualTo(CarrierLocation.DESTINATION);
 }

 @Test void aCarrierFoundBackAtTheSourceCancelsTheOrder(){
  long id=order("OHT-C");
  advance(id,OrderStatus.ASSIGNED,OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.RECOVERY_REQUIRED);
  var resolved=orders.resolveRecovery(SITE,id,CarrierLocation.SOURCE,"put back by hand","test",null);
  assertThat(resolved.status()).isEqualTo(OrderStatus.CANCELLED);
  assertThatThrownBy(()->orders.resolveRecovery(SITE,id,CarrierLocation.SOURCE,"again","test",null))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("ORDER_NOT_IN_RECOVERY"));
 }

 // ---- no road outside a driving stage (R3) ----

 @Test void loadingTakesBackAnUnsentMoveAndRecoveryGetsNoMoreRoad(){
  long id=assigned("OHT-C");
  advance(id,OrderStatus.TO_SOURCE,OrderStatus.LOADING);
  // The vehicle is at the port; the approach MOVE that never left is now stale.
  assertThat(outbox.byOrder(SITE,id)).extracting(DeviceCommand::status).containsOnly(CommandStatus.WITHDRAWN);
  advance(id,OrderStatus.TO_DESTINATION);
  var delivery=movement.resume(SITE,id);
  assertThat(delivery.decision()).isEqualTo("RESUMED");
  acknowledge(id);
  arrive(102,false);
  advance(id,OrderStatus.RECOVERY_REQUIRED);
  assertThat(movement.extend(SITE,id).decision()).isEqualTo("ORDER_NOT_MOVABLE");
  standAt(102,true);
  assertThat(movement.resume(SITE,id).decision()).isEqualTo("ORDER_NOT_MOVABLE");
 }

 // ---- the first movement ends at the pickup (R4) ----

 @Test void theFirstMovementEndsAtThePickupAndDeliveryFollowsThePickupReport() throws Exception {
  standAt(118,true);
  long id=assigned("OHT-C");
  var move=outbox.byOrder(SITE,id).getFirst();
  assertThat(authorized(move)).containsExactly(118L,119L,120L,SOURCE);
  // The route is stored whole; only the authority stops at the pickup.
  var payload=json.readTree(move.payload());
  assertThat(payload.get("deliveryNodeIds").get(payload.get("deliveryNodeIds").size()-1).asLong()).isEqualTo(105L);
  acknowledge(id);
  arrive(119,false);
  assertThat(movement.extend(SITE,id).decision()).isEqualTo("AUTHORIZED_TO_PICKUP");
  arrive(SOURCE,true);
  advance(id,OrderStatus.TO_SOURCE);
  assertThat(movement.resume(SITE,id).decision()).isEqualTo("AT_PICKUP");
  advance(id,OrderStatus.LOADING,OrderStatus.TO_DESTINATION);
  var delivery=movement.resume(SITE,id);
  assertThat(delivery.decision()).isEqualTo("RESUMED");
  assertThat(delivery.addedNodeIds()).containsExactly(102L,103L,104L,105L);
 }

 // ---- a state nobody has confirmed lately (R5) ----

 @Test void aVehicleStateNobodyHasConfirmedLatelyIsNotDispatchedOrMovedOn(){
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 day' WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  long id=order("OHT-C");
  assertThatThrownBy(()->assignment.assign(SITE,id,"test",null))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("VEHICLE_STATE_STALE"));
  assertThat(assignment.active(SITE)).noneMatch(a->a.orderId()==id);

  standAt(SOURCE,true);
  long moving=assigned("OHT-C");
  advance(moving,OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.TO_DESTINATION);
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 day' WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  assertThat(movement.resume(SITE,moving).decision()).isEqualTo("POSITION_STALE");
  jdbc.update("UPDATE fleet.vehicle_state SET idle=FALSE WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  assertThat(movement.extend(SITE,moving).decision()).isEqualTo("POSITION_STALE");
 }

 // ---- standing somewhere blocks it (R7) ----

 @Test void aVehicleStandingOnANodeItWasNeverGivenStillBlocksIt(){
  standAt(102,true);
  fleet.report(SITE,REVISION,OTHER,new VehicleReport(SOURCE,true,true,true,false,"SIMULATED"));
  assertThat(traffic.advanced(SITE,OHT,102L).authorized()).isFalse();
  var grant=traffic.request(SITE,REVISION,OTHER,null,List.of(SOURCE,102L,103L));
  assertThat(grant.nodeIds()).containsExactly(SOURCE);
  assertThat(grant.reason()).isEqualTo("NODE_OCCUPIED_BY_OTHER_VEHICLE");
  assertThat(grant.blockedByVehicleId()).isEqualTo(OHT);
  // Each now waits for where the other stands: a deadlock even though only one of them holds anything.
  var back=traffic.request(SITE,REVISION,OHT,null,List.of(102L,SOURCE));
  assertThat(back.blockedByVehicleId()).isEqualTo(OTHER);
  assertThat(traffic.deadlocks(SITE)).anySatisfy(d->assertThat(d.vehicleIds()).containsExactlyInAnyOrder(OHT,OTHER));
 }
}
