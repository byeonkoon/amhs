package com.amhs;
import com.amhs.traffic.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Reservation is permission to occupy, not a plan. These are the cases where treating it as a
 * plan, or freeing it on anything other than evidence of arrival, puts two vehicles in one place.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TrafficReservationSqlTest {
 @Autowired TrafficControl traffic; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;

 // The draft revision has two separate six-node networks; the OHT one runs 1101 -> 1104.
 private static final long SITE=1, REVISION=2;
 private static final long A=1101,B=1102,C=1103,D=1104;
 private static final long ONE=9101,TWO=9102,THREE=9103;

 @BeforeEach void clearReservations(){
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE topology.node SET enabled=TRUE WHERE site_id=? AND revision_id=?",SITE,REVISION);
  jdbc.update("UPDATE platform.site_operation SET node_count=20,more_remaining_nodes=3 WHERE site_id=?",SITE);
 }
 private ReservationGrant request(long vehicle,List<Long> route){
  return tx.execute(s->traffic.request(SITE,REVISION,vehicle,null,route));
 }
 private int releaseAhead(long vehicle,Long position,String reason){
  Integer released=tx.execute(s->traffic.releaseAhead(SITE,vehicle,position,reason));
  return released==null?0:released;
 }
 private List<Long> heldNodes(long vehicle){
  return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList();
 }

 @Test void aFreeRunIsGrantedWholeAndOnlyToItsHolder(){
  var grant=request(ONE,List.of(A,B,C,D));
  assertThat(grant.complete()).isTrue();
  assertThat(grant.nodeIds()).containsExactly(A,B,C,D);
  assertThat(heldNodes(ONE)).containsExactly(A,B,C,D);
  assertThat(traffic.held(SITE)).allMatch(h->h.vehicleId()==ONE);
 }

 @Test void theRunStopsAtTheFirstBlockedNodeAndDoesNotResumePastIt(){
  request(TWO,List.of(C));
  var grant=request(ONE,List.of(A,B,C,D));
  // D is free, but granting it would tell the vehicle it may pass straight through C.
  assertThat(grant.nodeIds()).containsExactly(A,B);
  assertThat(grant.complete()).isFalse();
  assertThat(grant.blockedNodeId()).isEqualTo(C);
  assertThat(grant.blockedByVehicleId()).isEqualTo(TWO);
  assertThat(grant.reason()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
  assertThat(heldNodes(ONE)).containsExactly(A,B);
 }

 @Test void aNodeTakenOutOfServiceStopsTheRunJustLikeAVehicleWould(){
  jdbc.update("UPDATE topology.node SET enabled=FALSE WHERE id=?",C);
  var grant=request(ONE,List.of(A,B,C,D));
  assertThat(grant.nodeIds()).containsExactly(A,B);
  assertThat(grant.reason()).isEqualTo("NODE_NOT_AVAILABLE");
  assertThat(grant.blockedByVehicleId()).isNull();
 }

 @Test void theMovementAuthorityNeverExceedsTheConfiguredNodeCount(){
  jdbc.update("UPDATE platform.site_operation SET more_remaining_nodes=1,node_count=2 WHERE site_id=?",SITE);
  var grant=request(ONE,List.of(A,B,C,D));
  assertThat(grant.nodeIds()).containsExactly(A,B);
  // Stopping because the authority ran out is not being blocked by anything.
  assertThat(grant.complete()).isTrue();
  assertThat(grant.blockedNodeId()).isNull();
 }

 @Test void askingAgainExtendsTheSameRunWithoutDuplicatingWhatIsHeld(){
  jdbc.update("UPDATE platform.site_operation SET more_remaining_nodes=1,node_count=2 WHERE site_id=?",SITE);
  request(ONE,List.of(A,B));
  jdbc.update("UPDATE platform.site_operation SET node_count=4 WHERE site_id=?",SITE);
  var extended=request(ONE,List.of(A,B,C,D));
  assertThat(extended.nodeIds()).containsExactly(A,B,C,D);
  assertThat(heldNodes(ONE)).containsExactly(A,B,C,D);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation"
   +" WHERE vehicle_id=? AND released_at IS NULL",Integer.class,ONE)).isEqualTo(4);
 }

 @Test void onlyEvidenceOfArrivalFreesWhatIsBehind(){
  request(ONE,List.of(A,B,C,D));
  var progress=tx.execute(s->traffic.advanced(SITE,ONE,C));
  assertThat(progress.authorized()).isTrue();
  assertThat(progress.released()).isEqualTo(2);
  // The node it stands on and the road ahead stay held; only what it has left is given back.
  assertThat(heldNodes(ONE)).containsExactly(C,D);
 }

 @Test void aReportFromOutsideTheGrantedRunReleasesNothing(){
  request(ONE,List.of(A,B));
  var progress=tx.execute(s->traffic.advanced(SITE,ONE,D));
  assertThat(progress.authorized()).isFalse();
  assertThat(progress.released()).isZero();
  assertThat(heldNodes(ONE)).containsExactly(A,B);
 }

 @Test void aVehicleNobodyCanLocateKeepsEverythingItHolds(){
  request(ONE,List.of(A,B,C,D));
  assertThat(releaseAhead(ONE,null,"ORDER_CANCELLED")).isZero();
  assertThat(heldNodes(ONE)).containsExactly(A,B,C,D);
  // With a position, the road ahead is given back but the vehicle keeps where it is standing.
  assertThat(releaseAhead(ONE,B,"ORDER_CANCELLED")).isEqualTo(2);
  assertThat(heldNodes(ONE)).containsExactly(A,B);
 }

 @Test void twoVehiclesWaitingOnEachOtherAreReportedAsOneChain(){
  request(ONE,List.of(A));
  request(TWO,List.of(B));
  // Each now asks for the node the other is standing on.
  var first=request(ONE,List.of(A,B));
  var second=request(TWO,List.of(B,A));
  assertThat(first.complete()).isFalse();
  assertThat(second.complete()).isFalse();
  var deadlock=traffic.deadlock(SITE,ONE).orElseThrow();
  assertThat(deadlock.vehicleIds()).containsExactlyInAnyOrder(ONE,TWO);
  assertThat(deadlock.wantedNodeIds()).containsExactlyInAnyOrder(A,B);
  assertThat(traffic.deadlocks(SITE)).hasSize(1);
 }

 @Test void aQueueThatIsMerelySlowIsNotReportedAsADeadlock(){
  request(ONE,List.of(A,B));
  // TWO waits behind ONE, but ONE is not waiting for anything, so the chain ends.
  var blocked=request(TWO,List.of(C,B));
  assertThat(blocked.complete()).isFalse();
  assertThat(traffic.deadlock(SITE,TWO)).isEmpty();
  assertThat(traffic.deadlocks(SITE)).isEmpty();
 }

 @Test void aChainThatHasSinceClearedIsNotReported(){
  request(ONE,List.of(A));
  request(TWO,List.of(B));
  request(ONE,List.of(A,B));
  request(TWO,List.of(B,A));
  assertThat(traffic.deadlocks(SITE)).hasSize(1);
  // ONE moves out of A entirely, so nothing is waiting on it any more even though the stale
  // wait rows are still there.
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now() WHERE vehicle_id=? AND node_id=?",ONE,A);
  assertThat(traffic.deadlocks(SITE)).isEmpty();
 }

 @Test void standingOnANodeSomebodyElseHoldsIsNamedApartFromCongestion(){
  request(TWO,List.of(A));
  var grant=request(ONE,List.of(A,B));
  assertThat(grant.stalled()).isTrue();
  assertThat(grant.reason()).isEqualTo("POSITION_HELD_BY_OTHER_VEHICLE");
  assertThat(grant.blockedByVehicleId()).isEqualTo(TWO);
 }

 @Test void oneAuthorityNeverCoversTheSameNodeTwice(){
  var grant=request(ONE,List.of(A,B,C,B,A));
  assertThat(grant.nodeIds()).containsExactly(A,B,C);
 }

 @Test void separateNetworksDoNotCompeteForNodes(){
  var oht=request(ONE,List.of(A,B));
  var agv=request(THREE,List.of(1201L,1202L));
  assertThat(oht.complete()).isTrue();
  assertThat(agv.complete()).isTrue();
  assertThat(traffic.deadlocks(SITE)).isEmpty();
 }
}
