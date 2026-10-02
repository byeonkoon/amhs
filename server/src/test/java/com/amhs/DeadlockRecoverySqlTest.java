package com.amhs;
import com.amhs.fleet.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Breaking a deadlock by hand: the chain shows which vehicle could give way; the chosen one is told
 * to stop and keeps its road until it is seen standing still after the stop settled; then it gives
 * back only the road ahead of where it stands and asks for nothing new until the vehicle it gave way
 * to has taken the node. A chain whose waited-for nodes are all stood on cannot be undone this way.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class DeadlockRecoverySqlTest {
 @Autowired DeadlockRecoveries recoveries; @Autowired TrafficControl traffic; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT1=1, AGV1=2, AGV2=4;

 @BeforeEach @AfterEach void clean(){
  jdbc.update("UPDATE traffic.deadlock_recovery SET status='ABANDONED',finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20 WHERE site_id=?",SITE);
 }

 private void place(long vehicle,long node){
  fleet.report(SITE,REVISION,vehicle,new VehicleReport(node,true,true,true,false,"SIMULATED"));
 }
 private ReservationGrant request(long vehicle,List<Long> route){
  return tx.execute(s->traffic.request(SITE,REVISION,vehicle,null,route));
 }
 private List<Long> held(long vehicle){ return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList(); }
 private List<DeadlockRecoveries.Step> advance(){ return tx.execute(s->recoveries.advance(SITE)); }
 private DeadlockRecovery recovery(long id){ return traffic.recovery(SITE,id).orElseThrow(); }
 private String decisionFor(List<DeadlockRecoveries.Step> steps,long id){
  return steps.stream().filter(s->s.recoveryId()==id).map(DeadlockRecoveries.Step::decision).findFirst().orElse(null);
 }

 /**
  * AGV-01 stands on 203 holding 203, 204, 207 and waits for 208; AGV-02 stands on 206 holding 206
  * and (from an earlier grant) 208, and waits for 207. Each waits for road the other holds ahead.
  */
 private void crossedRoads(){
  place(AGV1,203L); place(AGV2,206L);
  request(AGV2,List.of(206L));
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,order_id,sequence) VALUES(?,?,?,?,NULL,100000)",
   SITE,REVISION,208L,AGV2);
  assertThat(request(AGV1,List.of(203L,204L,207L,208L)).blockedNodeId()).isEqualTo(208L);
  assertThat(request(AGV2,List.of(206L,207L)).blockedNodeId()).isEqualTo(207L);
 }

 @Test void theChosenVehicleStopsThenGivesBackOnlyItsRoadAheadAndWaitsUntilTheOtherHasPassed(){
  crossedRoads();
  var plans=recoveries.plans(SITE);
  assertThat(plans).hasSize(1);
  assertThat(plans.getFirst().candidates()).containsExactlyInAnyOrder(AGV1,AGV2);
  assertThat(plans.getFirst().links()).allMatch(DeadlockPlan.Link::releasable);

  var started=tx.execute(s->recoveries.start(SITE,AGV1,"AGV-02 must get through first","tester"));
  assertThat(started.status()).isEqualTo(DeadlockRecovery.STOPPING);
  assertThat(started.beneficiaryVehicleId()).isEqualTo(AGV2);
  assertThat(started.wantedNodeId()).isEqualTo(207L);
  assertThat(jdbc.queryForObject("SELECT command_type||':'||status FROM integration.command_outbox WHERE command_id=?",
   String.class,started.stopCommandId())).isEqualTo("STOP:PENDING");
  assertThat(recoveries.plans(SITE).getFirst().recoveryId()).isEqualTo(started.id());

  // While it gives way it gets nothing new, and is not recorded as waiting.
  var more=request(AGV1,List.of(203L,204L,207L,208L,209L));
  assertThat(more.nodeIds()).containsExactly(203L,204L,207L);
  assertThat(more.reason()).isEqualTo("DEADLOCK_RECOVERY");
  assertThat(held(AGV1)).containsExactly(203L,204L,207L);

  // The stop has not settled: it keeps its road.
  assertThat(decisionFor(advance(),started.id())).isEqualTo("STOP_OUTSTANDING");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED',settled_at=now() WHERE command_id=?",started.stopCommandId());
  assertThat(decisionFor(advance(),started.id())).isEqualTo("WAITING_FOR_STANDSTILL");
  // Settled, but no report since: it may still be rolling on what it was given.
  assertThat(decisionFor(advance(),started.id())).isEqualTo("WAITING_FOR_STANDSTILL");
  assertThat(held(AGV1)).containsExactly(203L,204L,207L);

  // Seen standing on 203 after the stop: the road ahead goes back, the node it stands on stays.
  place(AGV1,203L);
  assertThat(decisionFor(advance(),started.id())).isEqualTo("ROAD_GIVEN_BACK");
  assertThat(held(AGV1)).containsExactly(203L);
  var yielding=recovery(started.id());
  assertThat(yielding.status()).isEqualTo(DeadlockRecovery.YIELDING);
  assertThat(yielding.releasedNodeIds()).containsExactlyInAnyOrder(204L,207L);
  assertThat(request(AGV1,List.of(203L,204L)).nodeIds()).containsExactly(203L);

  // AGV-02 takes 207; then AGV-01 is done giving way and asks for road as usual.
  assertThat(request(AGV2,List.of(206L,207L)).nodeIds()).containsExactly(206L,207L);
  assertThat(decisionFor(advance(),started.id())).isEqualTo("BENEFICIARY_TOOK_NODE");
  assertThat(recovery(started.id()).status()).isEqualTo(DeadlockRecovery.DONE);
  assertThat(request(AGV1,List.of(203L,204L)).nodeIds()).containsExactly(203L,204L);
  assertThat(traffic.deadlocks(SITE)).isEmpty();
 }

 @Test void whenEveryWaitedNodeIsStoodOnGivingWayCannotHelpAndAReasonIsRequired(){
  place(AGV1,203L); place(AGV2,204L);
  request(AGV1,List.of(203L)); request(AGV2,List.of(204L));
  request(AGV1,List.of(203L,204L)); request(AGV2,List.of(204L,203L));
  var plan=recoveries.plans(SITE).getFirst();
  assertThat(plan.candidates()).isEmpty();
  assertThat(plan.links()).allMatch(DeadlockPlan.Link::blockerStandsThere);
  assertThatThrownBy(()->tx.execute(s->recoveries.start(SITE,AGV1,"try","tester")))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("GIVING_WAY_WOULD_NOT_HELP"));
  assertThatThrownBy(()->tx.execute(s->recoveries.start(SITE,AGV1," ","tester")))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("REASON_REQUIRED"));
  assertThat(traffic.recoveries(SITE,0)).isEmpty();
 }

 @Test void oneVehiclePerChainAndCallingItOffTakesBackAnUnsentStop(){
  crossedRoads();
  place(OHT1,101L);
  assertThatThrownBy(()->tx.execute(s->recoveries.start(SITE,OHT1,"not stuck","tester")))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NOT_IN_DEADLOCK"));
  var started=tx.execute(s->recoveries.start(SITE,AGV2,"let AGV-01 through","tester"));
  assertThatThrownBy(()->tx.execute(s->recoveries.start(SITE,AGV1,"both","tester")))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("RECOVERY_ALREADY_OPEN"));
  var closed=tx.execute(s->recoveries.close(SITE,started.id()));
  assertThat(closed.status()).isEqualTo(DeadlockRecovery.ABANDONED);
  assertThat(jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,started.stopCommandId()))
   .isEqualTo("WITHDRAWN");
  // Nothing was given back, and it asks for road again as before.
  assertThat(held(AGV2)).containsExactly(206L,208L);
  assertThat(request(AGV2,List.of(206L,207L)).reason()).isEqualTo("NODE_HELD_BY_OTHER_VEHICLE");
  assertThat(traffic.deadlocks(SITE)).hasSize(1);
 }

 @Test void aChainThatClearsByItselfEndsTheRecovery(){
  crossedRoads();
  var started=tx.execute(s->recoveries.start(SITE,AGV1,"AGV-02 first","tester"));
  // AGV-02 gives up on 207 (its order went away, say): nobody waits for that node any more.
  tx.executeWithoutResult(s->traffic.releaseAhead(SITE,AGV2,206L,"TEST"));
  assertThat(decisionFor(advance(),started.id())).isEqualTo("NO_LONGER_WAITED_FOR");
  assertThat(recovery(started.id()).status()).isEqualTo(DeadlockRecovery.DONE);
  assertThat(held(AGV1)).containsExactly(203L,204L,207L);
  // The stop it never sent is taken back, so it cannot cut the vehicle's next move short.
  assertThat(jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,started.stopCommandId()))
   .isEqualTo("WITHDRAWN");
 }
}
