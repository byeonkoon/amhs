package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Two ways a hand-made deadlock recovery used to fail to reach the vehicle.
 *
 * <p>Commands for one vehicle leave in the order they were written, so anything left unsettled in
 * front of the stop holds the stop back. And one recovery per chain is a statement about the chain,
 * which has more than one vehicle in it.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class RecoveryWithdrawalSqlTest {
 @Autowired DeadlockRecoveries recoveries; @Autowired TrafficControl traffic; @Autowired FleetState fleet;
 @Autowired CommandOutbox outbox; @Autowired TransportLedger ledger;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2, AGV2=4;

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
 /** AGV-01 and AGV-02 each wait for road the other holds ahead; both could give way. */
 private void crossedRoads(){
  place(AGV1,203L); place(AGV2,206L);
  request(AGV2,List.of(206L));
  jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,order_id,sequence)"
   +" VALUES(?,?,?,?,NULL,100000)",SITE,REVISION,208L,AGV2);
  request(AGV1,List.of(203L,204L,207L,208L));
  request(AGV2,List.of(206L,207L));
 }
 /** Typed so the assertion below picks the list overload rather than a functional one. */
 private List<DeviceCommand> claim(){ return tx.execute(s->outbox.claim(SITE,50,5)); }
 private String statusOf(String commandId){
  return jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,commandId);
 }

 /**
  * An order's movement is as much in the way as an order-less one. One left FAILED in front of the
  * stop means the stop is never claimed and the vehicle is never told to give way.
  */
 @Test void anOrdersMovementBeingSentIsTakenBackSoTheStopCanLeave(){
  crossedRoads();
  // A movement of AGV-01's order is being sent when the operator decides it must give way.
  long orderId=jdbc.queryForObject("SELECT id FROM transport.order WHERE site_id=? ORDER BY id LIMIT 1",Long.class,SITE);
  String moveId="test-order-move-"+System.nanoTime();
  jdbc.update("INSERT INTO integration.command_outbox(site_id,order_id,vehicle_id,command_id,command_type,payload,status,attempt)"
   +" VALUES(?,?,?,?,'MOVE','{}','DISPATCHING',1)",SITE,orderId,AGV1,moveId);

  var started=tx.execute(s->recoveries.start(SITE,AGV1,"AGV-02 must get through first","tester"));

  // Being sent, so it is not withdrawn outright: the request is recorded and the send's own result
  // decides. A send that provably failed ends WITHDRAWN rather than FAILED.
  assertThat(jdbc.queryForObject("SELECT withdraw_requested_at IS NOT NULL FROM integration.command_outbox"
   +" WHERE command_id=?",Boolean.class,moveId)).isTrue();
  tx.execute(s->outbox.settle(moveId,DeviceChannel.Verdict.notDelivered("LINK_DOWN"),Duration.ZERO));
  assertThat(statusOf(moveId)).isEqualTo("WITHDRAWN");

  // With nothing unsettled in front of it, the stop is claimable.
  var claimed=claim();
  assertThat(claimed).extracting(DeviceCommand::commandId).contains(started.stopCommandId());
 }

 /** A movement that never left is taken back outright, order or not. */
 @Test void anOrdersMovementThatNeverLeftIsWithdrawnOutright(){
  crossedRoads();
  long orderId=jdbc.queryForObject("SELECT id FROM transport.order WHERE site_id=? ORDER BY id LIMIT 1",Long.class,SITE);
  String moveId="test-order-pending-"+System.nanoTime();
  jdbc.update("INSERT INTO integration.command_outbox(site_id,order_id,vehicle_id,command_id,command_type,payload,status)"
   +" VALUES(?,?,?,?,'MORE','{}','PENDING')",SITE,orderId,AGV1,moveId);
  var started=tx.execute(s->recoveries.start(SITE,AGV1,"AGV-02 must get through first","tester"));
  assertThat(statusOf(moveId)).isEqualTo("WITHDRAWN");
  assertThat(claim()).extracting(DeviceCommand::commandId).contains(started.stopCommandId());
 }

 /**
  * Two operators naming different vehicles of the same chain at the same moment. The decision is
  * about the chain, so exactly one of them may start a recovery.
  */
 @Test void twoVehiclesOfOneChainCannotBothBeToldToGiveWay() throws Exception {
  crossedRoads();
  var ready=new CyclicBarrier(2);
  var pool=Executors.newFixedThreadPool(2);
  try{
   var first=pool.submit(()->startAfter(ready,AGV1));
   var second=pool.submit(()->startAfter(ready,AGV2));
   var outcomes=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
   assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
   assertThat(outcomes).filteredOn(o->!"OK".equals(o)).singleElement()
    .satisfies(o->assertThat(o).contains("RECOVERY_ALREADY_OPEN"));
  }finally{ pool.shutdownNow(); }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic.deadlock_recovery WHERE site_id=? AND finished_at IS NULL",
   Integer.class,SITE)).isEqualTo(1);
 }
 private String startAfter(CyclicBarrier ready,long vehicle){
  try{
   ready.await(20,TimeUnit.SECONDS);
   tx.execute(s->recoveries.start(SITE,vehicle,"both operators chose at once","tester"));
   return "OK";
  }catch(TransportRejected rejected){ return rejected.code();
  }catch(Exception e){ return e.getClass().getSimpleName()+":"+e.getMessage(); }
 }
}
