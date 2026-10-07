package com.amhs;
import com.amhs.alarm.SiteAlarms;
import com.amhs.fleet.*;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A30: a cancel racing the sender, and transfer reports that arrive late.
 *
 * <ul>
 * <li>A cancel and a send run at the same time never end as "cancelled" with the movement sent: the
 * cancel either wins before the claim (nothing leaves) or waits for the vehicle to confirm.</li>
 * <li>A send that was claimed before the cancel and then fails is withdrawn, not queued again.</li>
 * <li>A pickup reported after a cancel was asked for is not refused and not lost: the status stays,
 * the carrier is recorded aboard, and the cancel confirmation ends in recovery. A pickup that did
 * not happen leaves the cancel to end plainly.</li>
 * <li>A report repeated changes nothing. A report about a finished order does not reopen it; a
 * carrier somewhere else than recorded becomes late evidence and an alarm until a person closes it.</li>
 * </ul>
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class CancelAndLateReportSqlTest {
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment; @Autowired CommandOutbox outbox;
 @Autowired FleetState fleet; @Autowired SiteAlarms alarms; @Autowired JdbcTemplate jdbc; @Autowired ContinuousMovement movement;
 private static final long SITE=1, REVISION=1, OHT1=1;

 @BeforeEach @AfterEach void clean(){
  jdbc.update("UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='test' WHERE finished_at IS NULL");
  jdbc.update("UPDATE platform.site_operation SET restart_check_since=NULL,sequence_running=TRUE,node_count=20 WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("UPDATE transport.late_evidence SET resolved_at=now(),resolved_by='test',resolution='test' WHERE resolved_at IS NULL");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT1);
 }
 @AfterEach void restore(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT1);
 }

 /** OHT-01 at 101, assigned an OHT-A to OHT-C order; its first movement waits in the outbox. */
 private long assigned(){
  fleet.report(SITE,REVISION,OHT1,new VehicleReport(101L,true,true,true,false,"SIMULATED"));
  String key="cancel-race-"+System.nanoTime();
  long id=orders.accept(SITE,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  assignment.assign(SITE,id,"tester",null);
  return id;
 }
 private String moveOf(long orderId){
  return jdbc.queryForObject("SELECT command_id FROM integration.command_outbox WHERE order_id=? AND command_type='MOVE'"
   +" ORDER BY id LIMIT 1",String.class,orderId);
 }
 private String status(String commandId){
  return jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,commandId);
 }
 private OrderStatus statusOf(long orderId){ return orders.find(SITE,orderId).orElseThrow().status(); }
 private long loading(){
  long id=assigned();
  orders.advance(SITE,id,OrderStatus.TO_SOURCE,"test","tester",null);
  orders.advance(SITE,id,OrderStatus.LOADING,"test","tester",null);
  return id;
 }

 @Test void aSendClaimedBeforeTheCancelThatThenFailsIsWithdrawnNotQueuedAgain(){
  long id=assigned();
  String move=moveOf(id);
  var claimed=outbox.claim(SITE,10,5);
  assertThat(claimed).anySatisfy(c->assertThat(c.commandId()).isEqualTo(move));
  assertThat(orders.cancel(SITE,id,"no longer needed","tester",null).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
  outbox.settle(move,DeviceChannel.Verdict.notDelivered("link down"),Duration.ZERO);
  assertThat(status(move)).isEqualTo("WITHDRAWN");
  assertThat(outbox.claim(SITE,10,5)).noneSatisfy(c->assertThat(c.commandId()).isEqualTo(move));
 }

 @Test void aCancelRacingTheSenderNeverEndsCancelledWithTheMovementSent() throws Exception {
  var pool=Executors.newFixedThreadPool(2);
  int cancelledFirst=0,sentFirst=0;
  try{
   for(int round=0;round<12;round++){
    clean();
    long id=assigned();
    String move=moveOf(id);
    var start=new CyclicBarrier(2);
    Future<?> send=pool.submit(()->{
     start.await();
     for(var c:outbox.claim(SITE,10,5)) outbox.settle(c.commandId(),DeviceChannel.Verdict.delivered(),Duration.ZERO);
     return null;
    });
    Future<OrderStatus> cancel=pool.submit(()->{
     start.await();
     return orders.cancel(SITE,id,"race","tester",null).status();
    });
    send.get(10,TimeUnit.SECONDS);
    var after=cancel.get(10,TimeUnit.SECONDS);
    String sent=status(move);
    if(after==OrderStatus.CANCELLED) cancelledFirst++; else sentFirst++;
    if(after==OrderStatus.CANCELLED) assertThat(sent).as("round %d: cancelled, so nothing may have left",round).isEqualTo("WITHDRAWN");
    else{
     assertThat(after).as("round %d",round).isEqualTo(OrderStatus.CANCEL_REQUESTED);
     assertThat(sent).as("round %d: the vehicle has it, so the cancel waits",round).isIn("SENT","ACCEPTED");
    }
   }
  }finally{ pool.shutdownNow(); }
  // Which way each round fell is up to the scheduler; both ways are held to the same rule.
  System.out.println("cancel race: cancelled first "+cancelledFirst+", sent first "+sentFirst);
 }

 @Test void aPickupReportedAfterTheCancelWasAskedForEndsInRecovery(){
  long id=loading();
  assertThat(orders.cancel(SITE,id,"no longer needed","tester",null).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
  var after=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE),"device",null);
  assertThat(after.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
  assertThat(after.carrierLocation()).isEqualTo(CarrierLocation.VEHICLE);
  assertThat(orders.events(SITE,id)).anySatisfy(e->assertThat(e.reason()).startsWith("LATE_REPORT"));
  // The vehicle confirms it stopped: the carrier is aboard, so a person has to say where it goes.
  assertThat(orders.advance(SITE,id,OrderStatus.CANCELLED,"stopped","device",null).status())
   .isEqualTo(OrderStatus.RECOVERY_REQUIRED);
 }

 @Test void aPickupThatDidNotHappenLetsTheCancelEndPlainly(){
  long id=loading();
  orders.cancel(SITE,id,"no longer needed","tester",null);
  var after=orders.reportTransfer(SITE,id,OrderStatus.TO_SOURCE,TransitionEvidence.sensor(CarrierLocation.SOURCE),"device",null);
  assertThat(after.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
  assertThat(after.carrierLocation()).isEqualTo(CarrierLocation.SOURCE);
  assertThat(orders.advance(SITE,id,OrderStatus.CANCELLED,"stopped","device",null).status()).isEqualTo(OrderStatus.CANCELLED);
 }

 @Test void aRepeatedReportIsNotASecondEvent(){
  long id=loading();
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE),"device",null);
  int events=orders.events(SITE,id).size();
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE),"device",null);
  assertThat(orders.events(SITE,id)).hasSize(events);
  assertThat(statusOf(id)).isEqualTo(OrderStatus.TO_DESTINATION);
 }

 @Test void aCarrierReportedAfterTheOrderEndedIsKeptForAPersonAndNotReopened(){
  long id=assigned();
  assertThat(orders.cancel(SITE,id,"before pickup","tester",null).status()).isEqualTo(OrderStatus.CANCELLED);
  var after=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE),"device",null);
  assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
  var open=orders.openLateEvidence(SITE);
  assertThat(open).anySatisfy(l->{ assertThat(l.orderId()).isEqualTo(id); assertThat(l.reportedLocation()).isEqualTo("VEHICLE"); });
  long lateId=open.stream().filter(l->l.orderId()==id).findFirst().orElseThrow().id();
  assertThat(alarms.alarms(SITE)).anySatisfy(a->assertThat(a.key()).isEqualTo("LATE_EVIDENCE:"+lateId));
  assertThatThrownBy(()->orders.resolveLateEvidence(SITE,lateId," ","tester"))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("REASON_REQUIRED"));
  orders.resolveLateEvidence(SITE,lateId,"carrier found on OHT-01, moved back to the stocker","tester");
  assertThat(orders.openLateEvidence(SITE)).noneSatisfy(l->assertThat(l.id()).isEqualTo(lateId));
 }

 // Re-review M1-M3 of 30ba791, as the behaviour wanted.

 private static TransitionEvidence feedback(String command,CarrierLocation location){
  return new TransitionEvidence(TransitionEvidence.Kind.ACTION_FEEDBACK,command,location);
 }

 @Test void m1_reportsThatContradictEachOtherLeaveTheCarrierUnknownAndKeepTheVehicle(){
  long id=loading();
  orders.cancel(SITE,id,"cancel","tester",null);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,feedback("pickup-2",CarrierLocation.VEHICLE),"device",null);
  // An earlier failure arrives last. Which came first cannot be told, so neither is believed.
  var after=orders.reportTransfer(SITE,id,OrderStatus.TO_SOURCE,feedback("pickup-1",CarrierLocation.SOURCE),"device",null);
  assertThat(after.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(orders.openLateEvidence(SITE)).anySatisfy(l->{ assertThat(l.orderId()).isEqualTo(id); assertThat(l.kind()).isEqualTo("CONFLICT"); });
  assertThat(orders.advance(SITE,id,OrderStatus.CANCELLED,"stopped","device",null).status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(assignment.active(SITE)).anyMatch(a->a.orderId()==id);
 }

 @Test void m2_theSamePickupHeardAgainWhileUnloadingIsNotAFailedDrop(){
  long id=loading();
  var evidence=feedback("pickup-1",CarrierLocation.VEHICLE);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,evidence,"device",null);
  orders.advance(SITE,id,OrderStatus.UNLOADING,"arrived","device",null);
  int events=orders.events(SITE,id).size();
  var replay=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,evidence,"device",null);
  assertThat(replay.status()).isEqualTo(OrderStatus.UNLOADING);
  assertThat(jdbc.queryForObject("SELECT transfer_attempt FROM transport.order WHERE id=?",Integer.class,id)).isZero();
  assertThat(orders.events(SITE,id)).hasSize(events);
  // The same command saying something else is still about the pickup, not this drop.
  var other=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,feedback("pickup-1",CarrierLocation.VEHICLE),
   TransferAction.DROP,"device",null);
  assertThat(other.status()).isEqualTo(OrderStatus.UNLOADING);
  // A drop report says it is about the drop: that one is a retry.
  var drop=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,feedback("drop-1",CarrierLocation.VEHICLE),
   TransferAction.DROP,"device",null);
  assertThat(drop.status()).isEqualTo(OrderStatus.TO_DESTINATION);
 }

 @Test void m2_theSameLateReportRaisesOneAlarmAndNotAgainOnceClosed(){
  long id=assigned();
  orders.cancel(SITE,id,"cancel","tester",null);
  var evidence=feedback("pickup-1",CarrierLocation.VEHICLE);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,evidence,"device",null);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,evidence,"device",null);
  var open=orders.openLateEvidence(SITE).stream().filter(e->e.orderId()==id).toList();
  assertThat(open).hasSize(1);
  orders.resolveLateEvidence(SITE,open.getFirst().id(),"physically checked","tester");
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,evidence,"device",null);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,new TransitionEvidence(TransitionEvidence.Kind.SENSOR_TRANSITION,null,
   CarrierLocation.VEHICLE),"device",null);
  assertThat(orders.openLateEvidence(SITE).stream().filter(e->e.orderId()==id)).hasSizeLessThanOrEqualTo(1);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,new TransitionEvidence(TransitionEvidence.Kind.SENSOR_TRANSITION,null,
   CarrierLocation.VEHICLE),"device",null);
  assertThat(orders.openLateEvidence(SITE).stream().filter(e->e.orderId()==id)).hasSizeLessThanOrEqualTo(1);
 }

 @Test void m3_theDeviceTransitionEndpointJudgesALatePickupLikeAnyReport() throws Exception {
  long id=loading();
  orders.cancel(SITE,id,"cancel","tester",null);
  var constructor=Class.forName("com.amhs.transport.api.TransportOrderController").getDeclaredConstructors()[0];
  constructor.setAccessible(true);
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(constructor.newInstance(orders,assignment)).build();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sites/1/transport-orders/"+id+"/transitions")
   .contentType("application/json")
   .content("{\"status\":\"TO_DESTINATION\",\"evidence\":{\"kind\":\"SENSOR_TRANSITION\",\"carrierLocation\":\"VEHICLE\",\"action\":\"PICKUP\"}}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  assertThat(orders.events(SITE,id)).anyMatch(e->e.reason()!=null&&e.reason().startsWith("LATE_REPORT"));
  assertThat(orders.find(SITE,id).orElseThrow().carrierLocation()).isEqualTo(CarrierLocation.VEHICLE);
  assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCEL_REQUESTED);
 }

 // Review N1/N2 of be2dac4: a carrier in dispute is a person's to place; an old report repeated is still the same report.
 private long conflictDuringCancel(){
  long id=loading(); orders.cancel(SITE,id,"cancel","tester",null);
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,feedback("pickup-2",CarrierLocation.VEHICLE),TransferAction.PICKUP,"device",null);
  var conflict=orders.reportTransfer(SITE,id,OrderStatus.TO_SOURCE,feedback("pickup-1",CarrierLocation.SOURCE),TransferAction.PICKUP,"device",null);
  assertThat(conflict.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(conflict.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  return id;
 }
 @Test void n1_aDeviceCompletionDoesNotEndADisputedOrder(){
  long id=conflictDuringCancel();
  var after=orders.reportTransfer(SITE,id,OrderStatus.COMPLETED,feedback("drop-1",CarrierLocation.DESTINATION),TransferAction.DROP,"device",null);
  assertThat(after.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(after.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(assignment.active(SITE)).anyMatch(a->a.orderId()==id);
  // A person saying where the carrier is ends it.
  assertThat(orders.resolveRecovery(SITE,id,CarrierLocation.DESTINATION,"found at OHT-C","tester",null).status())
   .isEqualTo(OrderStatus.COMPLETED);
 }
 @Test void n1_aDisputeDuringDeliveryStopsTheVehicleBeingSentOn(){
  long id=loading();
  orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,feedback("pickup-2",CarrierLocation.VEHICLE),TransferAction.PICKUP,"device",null);
  fleet.report(SITE,REVISION,OHT1,new VehicleReport(101L,true,true,true,true,"SIMULATED"));
  var conflict=orders.reportTransfer(SITE,id,OrderStatus.TO_SOURCE,feedback("pickup-1",CarrierLocation.SOURCE),TransferAction.PICKUP,"device",null);
  assertThat(conflict.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(conflict.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(movement.resume(SITE,id).decision()).isNotEqualTo("RESUMED");
 }
 @Test void n2_aPickupRecordedBeforeTheReportLogIsNotTakenForAFailedDrop(){
  long id=loading();
  var proof=feedback("pre-log-pickup",CarrierLocation.VEHICLE);
  // How the earlier HTTP handler recorded it: straight into the order's history.
  orders.advance(SITE,id,OrderStatus.TO_DESTINATION,"before the report log","device",null,proof);
  orders.advance(SITE,id,OrderStatus.UNLOADING,"arrived","device",null);
  var replay=orders.reportTransfer(SITE,id,OrderStatus.TO_DESTINATION,proof,"device",null);
  assertThat(replay.status()).isEqualTo(OrderStatus.UNLOADING);
  assertThat(jdbc.queryForObject("SELECT transfer_attempt FROM transport.order WHERE id=?",Integer.class,id)).isZero();
 }
}
