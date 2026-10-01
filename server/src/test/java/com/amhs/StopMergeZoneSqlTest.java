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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Stop nodes, merge points and zones with a capacity.
 *
 * <p>The traffic cases run on the draft revision, whose OHT line is 1101 -> 1104, with a test zone
 * over its last two nodes. The bay case runs on the active layout: the OHT ring leaves at 108 into
 * a one-vehicle bay (121..123) and rejoins at 110.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class StopMergeZoneSqlTest {
 @Autowired TrafficControl traffic; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment; @Autowired TransportLedger ledger;
 @Autowired ContinuousMovement movement; @Autowired FleetState fleet;
 @Autowired CommandOutbox outbox; @Autowired DeviceGateway gateway;

 private static final long SITE=1, DRAFT=2, ACTIVE=1;
 private static final long A=1101,B=1102,C=1103,D=1104;
 private static final long ONE=9101,TWO=9102,THREE=9103,PHANTOM=9999;
 private static final long TEST_ZONE=9001;
 private static final long OHT=1, RING_START=101, BRANCH=108, BAY_ENTRY=121, BAY_PORT_NODE=122;
 private final String run=UUID.randomUUID().toString().substring(0,8);

 @BeforeEach void fresh(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id IN (?,?,?,?)",SITE,ONE,TWO,THREE,PHANTOM);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE,merge_point=FALSE WHERE site_id=? AND revision_id=?",SITE,DRAFT);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE WHERE site_id=? AND revision_id=?",SITE,ACTIVE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20,more_enabled=TRUE,more_type='NODE',more_remaining_nodes=3,"
   +"more_min_remaining_nodes=0,more_vehicle_signal=FALSE,merge_control='CONTROLLER',position_stale_seconds=10 WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM topology.zone WHERE id=?",TEST_ZONE);
  jdbc.update("INSERT INTO topology.zone(id,site_id,revision_id,code,capacity) VALUES(?,?,?,'TEST-ZONE',1)",TEST_ZONE,SITE,DRAFT);
  jdbc.update("INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id) VALUES(?,?,?,?),(?,?,?,?)",
   TEST_ZONE,SITE,DRAFT,C,TEST_ZONE,SITE,DRAFT,D);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=TRUE WHERE site_id=? AND revision_id=?",SITE,ACTIVE);
 }
 @AfterEach void restore(){
  jdbc.update("DELETE FROM topology.zone WHERE id=?",TEST_ZONE);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE,merge_point=FALSE WHERE site_id=? AND revision_id=?",SITE,DRAFT);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND revision_id=?",SITE,ACTIVE);
  jdbc.update("UPDATE platform.site_operation SET more_min_remaining_nodes=1 WHERE site_id=?",SITE);
 }

 private ReservationGrant request(long vehicle,List<Long> route){
  return tx.execute(s->traffic.request(SITE,DRAFT,vehicle,null,route));
 }
 private List<Long> held(long vehicle){ return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList(); }
 private void release(long vehicle){
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now() WHERE vehicle_id=? AND released_at IS NULL",vehicle);
 }

 // ---- stop nodes ----

 @Test void aStopNodeEndsTheRunWithoutCountingAsAWait(){
  jdbc.update("UPDATE topology.node SET stop_node=TRUE WHERE id=?",B);
  var grant=request(ONE,List.of(A,B,C,D));
  assertThat(grant.nodeIds()).containsExactly(A,B);
  assertThat(grant.complete()).isFalse();
  assertThat(grant.reason()).isEqualTo("STOP_NODE");
  assertThat(grant.blockedNodeId()).isNull();
  // Halting on purpose is not waiting for anybody.
  assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic.reservation_wait WHERE vehicle_id=?",Integer.class,ONE)).isZero();
  release(ONE);
  // Standing on the stop node, the vehicle can leave it again.
  var onward=request(ONE,List.of(B,C,D));
  assertThat(onward.complete()).isTrue();
  assertThat(onward.nodeIds()).containsExactly(B,C,D);
 }

 // ---- merge points ----

 @Test void aMergePointGoesToWhoeverAskedForItFirst(){
  jdbc.update("UPDATE topology.node SET merge_point=TRUE WHERE id=?",C);
  request(THREE,List.of(C));
  // Both arrive at the merge while it is occupied, ONE first and TWO after.
  assertThat(request(ONE,List.of(A,C)).reason()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
  assertThat(request(TWO,List.of(B,C)).reason()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
  release(THREE);
  // TWO happens to ask first once it is free, but ONE was waiting longer.
  var second=request(TWO,List.of(B,C));
  assertThat(second.reason()).isEqualTo("MERGE_EARLIER_REQUEST");
  assertThat(second.blockedByVehicleId()).isEqualTo(ONE);
  assertThat(second.nodeIds()).containsExactly(B);
  var first=request(ONE,List.of(A,C));
  assertThat(first.complete()).isTrue();
  assertThat(held(ONE)).containsExactly(A,C);
 }

 @Test void asksAgainForTheSameMergeKeepTheirPlaceInLine(){
  jdbc.update("UPDATE topology.node SET merge_point=TRUE WHERE id=?",C);
  request(THREE,List.of(C));
  request(ONE,List.of(A,C));
  request(TWO,List.of(B,C));
  // ONE is refused again and again; that must not move it behind TWO.
  request(ONE,List.of(A,C));
  request(ONE,List.of(A,C));
  release(THREE);
  assertThat(request(TWO,List.of(B,C)).reason()).isEqualTo("MERGE_EARLIER_REQUEST");
 }

 @Test void whenVehiclesDecideMergesTheControllerOnlyKeepsOneVehiclePerNode(){
  jdbc.update("UPDATE topology.node SET merge_point=TRUE WHERE id=?",C);
  jdbc.update("UPDATE platform.site_operation SET merge_control='VEHICLE_SIGNAL' WHERE site_id=?",SITE);
  request(THREE,List.of(C));
  request(ONE,List.of(A,C));
  release(THREE);
  // No queue at the merge: the order is left to the vehicles, the node is still held by one only.
  var grant=request(TWO,List.of(B,C));
  assertThat(grant.complete()).isTrue();
  assertThat(request(ONE,List.of(A,C)).reason()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
 }

 @Test void aWaitNobodyRenewsNoLongerHoldsAPlaceInLine(){
  jdbc.update("UPDATE topology.node SET merge_point=TRUE WHERE id=?",C);
  request(THREE,List.of(C));
  request(ONE,List.of(A,C));
  release(THREE);
  // ONE stopped asking long ago.
  jdbc.update("UPDATE traffic.reservation_wait SET seen_at=now()-interval '1 hour' WHERE vehicle_id=?",ONE);
  assertThat(request(TWO,List.of(B,C)).complete()).isTrue();
 }

 // ---- zones ----

 @Test void enteringAFullZoneIsRefusedAtItsFirstNode(){
  request(ONE,List.of(D));
  var grant=request(TWO,List.of(A,B,C,D));
  assertThat(grant.reason()).isEqualTo("ZONE_FULL");
  assertThat(grant.blockedNodeId()).isEqualTo(C);
  assertThat(grant.nodeIds()).containsExactly(A,B);
  // Once the vehicle inside has gone, the same request goes in.
  release(ONE);
  assertThat(request(TWO,List.of(B,C,D)).complete()).isTrue();
 }

 @Test void aVehicleStandingInTheZoneCountsEvenWithoutAReservation(){
  jdbc.update("INSERT INTO fleet.vehicle_state(site_id,vehicle_id,revision_id,node_id,source) VALUES(?,?,?,?,'SIMULATED')",
   SITE,THREE,DRAFT,D);
  assertThat(request(TWO,List.of(A,B,C)).reason()).isEqualTo("ZONE_FULL");
  assertThat(traffic.zones(SITE)).filteredOn(z->z.id()==TEST_ZONE).singleElement()
   .satisfies(z->{ assertThat(z.vehicleIds()).containsExactly(THREE); assertThat(z.full()).isTrue(); });
 }

 @Test void leavingAndMovingInsideAZoneAreNeverRefused(){
  request(ONE,List.of(C));
  // ONE is the one inside; going on within the zone and out of it is not an entry.
  jdbc.update("UPDATE topology.zone SET capacity=1 WHERE id=?",TEST_ZONE);
  assertThat(request(ONE,List.of(C,D)).complete()).isTrue();
 }

 @Test void capacityCountsAdmittedVehiclesNotOnlyArrivedOnes(){
  jdbc.update("UPDATE topology.zone SET capacity=2 WHERE id=?",TEST_ZONE);
  jdbc.update("INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id) VALUES(?,?,?,1106)",TEST_ZONE,SITE,DRAFT);
  assertThat(request(ONE,List.of(A,C)).complete()).isTrue();
  assertThat(request(TWO,List.of(B,D)).complete()).isTrue();
  // Two admitted, neither arrived: a third is refused even though its node is free.
  var third=request(THREE,List.of(1105L,1106L));
  assertThat(third.reason()).isEqualTo("ZONE_FULL");
  assertThat(third.blockedNodeId()).isEqualTo(1106L);
  release(ONE);
  assertThat(traffic.zones(SITE)).filteredOn(z->z.id()==TEST_ZONE).singleElement()
   .satisfies(z->assertThat(z.vehicleIds()).containsExactly(TWO));
 }

 // ---- a full bay on the active layout ----

 private void report(long node,boolean idle){
  tx.execute(s->fleet.report(SITE,ACTIVE,OHT,new VehicleReport(node,true,true,idle,false,"SIMULATED")));
 }
 private void arrive(long node,boolean idle){
  report(node,idle);
  tx.execute(s->traffic.advanced(SITE,OHT,node));
 }
 /** Picked up at the ring start and sent on the delivery leg, which is the one that enters the bay. */
 private long orderIntoTheBay(String key){
  long id=tx.execute(s->orders.accept(SITE,new OrderRequest("TEST",run+"-"+key,"OHT-A","OHT-E",run+"-"+key,50),
   "tester","trace").order().id());
  tx.execute(s->assignment.assign(SITE,id,"tester","trace"));
  for(var stage:List.of(OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.TO_DESTINATION))
   tx.execute(s->orders.advance(SITE,id,stage,"test","tester","trace",stage==OrderStatus.TO_DESTINATION?TransitionEvidence.sensor(CarrierLocation.VEHICLE):TransitionEvidence.system()));
  tx.execute(s->movement.resume(SITE,id));
  return id;
 }
 private void occupyTheBay(){
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,sequence) VALUES(?,?,?,?,1)",
   SITE,ACTIVE,BAY_PORT_NODE,PHANTOM);
 }
 private void acknowledgeAll(long orderId){
  // Acknowledgement only means something for a command that was actually attempted. Commands for
  // one vehicle leave one at a time, so each is acknowledged before the next one can be published.
  for(int pass=0;pass<40;pass++){
   gateway.publishPending(SITE);
   for(var c:outbox.byOrder(SITE,orderId))
    if(c.status()!=CommandStatus.PENDING&&c.status()!=CommandStatus.WITHDRAWN&&c.status()!=CommandStatus.ACCEPTED)
     tx.execute(s->outbox.acknowledge(c.commandId()));
   if(outbox.byOrder(SITE,orderId).stream().noneMatch(c->c.status()==CommandStatus.PENDING)) return;
  }
 }

 @Test void aVehicleHeadingIntoAFullBayGoesRoundTheRingInsteadOfStoppingAtTheBranch(){
  report(RING_START,true);
  occupyTheBay();
  long id=orderIntoTheBay("lap");
  var route=ledger.plannedRoute(id);
  // One extra lap of the ring was spliced in, so the branch now appears twice.
  assertThat(Collections.frequency(route,BRANCH)).isEqualTo(2);
  assertThat(route.getLast()).isEqualTo(BAY_PORT_NODE);
  // The vehicle runs on past the branch round the ring and holds nothing in the bay.
  assertThat(held(OHT)).contains(BRANCH,109L,120L).doesNotContain(BAY_ENTRY);
  assertThat(outbox.byOrder(SITE,id).stream()
   .filter(c->c.commandType().equals("MOVE")&&c.status()!=CommandStatus.WITHDRAWN)).hasSize(1);
 }

 @Test void onTheNextLapTheVehicleEntersOnceTheBayHasRoom(){
  report(RING_START,true);
  occupyTheBay();
  long id=orderIntoTheBay("enter");
  acknowledgeAll(id);
  // Coming round, two nodes before the end of its authority, and the bay has emptied meanwhile.
  release(PHANTOM);
  arrive(118,false);
  var extension=tx.execute(s->movement.extend(SITE,id));
  assertThat(extension.decision()).isEqualTo("EXTENDED");
  assertThat(extension.addedNodeIds()).contains(BRANCH,BAY_ENTRY,BAY_PORT_NODE);
 }

 @Test void whileTheBayStaysFullTheVehicleKeepsCircling(){
  report(RING_START,true);
  occupyTheBay();
  long id=orderIntoTheBay("circle");
  acknowledgeAll(id);
  int before=ledger.plannedRoute(id).size();
  arrive(118,false);
  var extension=tx.execute(s->movement.extend(SITE,id));
  assertThat(extension.decision()).isEqualTo("EXTENDED");
  assertThat(extension.addedNodeIds()).doesNotContain(BAY_ENTRY);
  // Another lap was added ahead of the bay entrance.
  assertThat(ledger.plannedRoute(id).size()).isGreaterThan(before);
  assertThat(Collections.frequency(ledger.plannedRoute(id),BRANCH)).isEqualTo(3);
 }
}
