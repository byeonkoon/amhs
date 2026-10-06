package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceGateway;
import com.amhs.simulation.SimulatedVehicles;
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
 * Review L1-L4 of b2b48d4, as the behaviour wanted: an open restart check holds every path that sends
 * or grants, not only the execution pass; an old standing report is not a present standstill; a
 * carrier the vehicle's report contradicts sends the work into recovery instead of resuming it; and a
 * road granted again after confirmation goes out as a new command rather than a withdrawn one.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class RestartGateSqlTest {
 @Autowired RestartCheck check; @Autowired TransportAssignment assignment; @Autowired TransportOrders orders;
 @Autowired ZoneEvacuation moves; @Autowired ContinuousMovement movement; @Autowired TrafficControl traffic;
 @Autowired FleetState fleet; @Autowired CommandOutbox outbox; @Autowired DeviceGateway gateway;
 @Autowired SimulatedVehicles simulator; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT1=1, AGV1=2;

 @BeforeEach void clean(){
  reset();
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=TRUE WHERE site_id=1 AND revision_id=1");
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION',auto_dispatch=FALSE,more_enabled=TRUE,more_type='NODE',"
   +"more_remaining_nodes=1,more_min_remaining_nodes=0,more_vehicle_signal=FALSE WHERE site_id=1");
  jdbc.update("UPDATE topology.node SET stop_node=FALSE WHERE site_id=1 AND revision_id=1");
 }
 @AfterEach void restore(){
  reset();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=1");
  // An earlier version of this test switched the sample AGVs' charging off; put back what the seed says.
  jdbc.update("UPDATE fleet.vehicle_profile SET charging_policy='NEAREST',charge_node_id=CASE id WHEN 2 THEN 212 ELSE 205 END"
   +" WHERE site_id=1 AND revision_id=1 AND id IN (2,4)");
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=1");
 }
 private void reset(){
  simulator.clear();
  jdbc.update("UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='test' WHERE finished_at IS NULL");
  jdbc.update("UPDATE platform.site_operation SET restart_check_since=NULL,node_count=20,sequence_running=TRUE WHERE site_id=?",SITE);
  jdbc.update("UPDATE traffic.deadlock_recovery SET status='ABANDONED',finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("DELETE FROM transport.charge_session");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
 }

 private void place(long vehicle,long node,boolean carrier){
  fleet.report(SITE,REVISION,vehicle,new VehicleReport(node,true,true,true,carrier,"SIMULATED"));
 }
 private void hold(long vehicle,List<Long> route){ tx.execute(s->traffic.request(SITE,REVISION,vehicle,null,route)); }
 private List<Long> held(long vehicle){ return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList(); }
 private String status(String commandId){
  return jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,commandId);
 }
 private RestartCheck.State begin(){ return tx.execute(s->check.begin(SITE)); }
 private List<String> kinds(RestartCheck.State st){ return st.items().stream().map(RestartCheck.Item::kind).toList(); }

 /** OHT-01 picks up at OHT-A and is on its way to OHT-C with the carrier aboard. */
 private long pickedUpOrder(){
  String key="restart-gate-"+System.nanoTime();
  jdbc.update("UPDATE platform.site_operation SET node_count=2 WHERE site_id=1");
  place(OHT1,101L,false);
  long id=orders.accept(SITE,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  assignment.assign(SITE,id,"tester",null);
  for(var stage:List.of(OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.TO_DESTINATION))
   orders.advance(SITE,id,stage,"test","tester",null,
    stage==OrderStatus.TO_DESTINATION?TransitionEvidence.sensor(CarrierLocation.VEHICLE):TransitionEvidence.system());
  place(OHT1,101L,true);
  return id;
 }

 @Test void l1_theSenderAndTheDirectGrantPathsWaitForTheCheckToo(){
  simulator.place(SITE,AGV1,203L);
  var move=moves.moveTo(SITE,AGV1,207L);
  assertThat(move.commandId()).isNotNull();
  assertThat(begin().pending()).isTrue();
  var published=gateway.publishPending(SITE);
  assertThat(published.claimed()).isZero();
  assertThat(status(move.commandId())).isEqualTo("PENDING");
 }

 @Test void l1_noNewRoadByMoreOrResumeWhileTheCheckIsOpen(){
  long id=pickedUpOrder();
  var first=movement.resume(SITE,id);
  assertThat(first.decision()).isEqualTo("RESUMED");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE command_id=?",first.commandId());
  fleet.report(SITE,REVISION,OHT1,new VehicleReport(102L,true,true,false,true,"SIMULATED",null,"REMOTE","MOVING"));
  traffic.advanced(SITE,OHT1,102L);
  begin();
  var more=movement.extend(SITE,id);
  assertThat(more.decision()).isEqualTo("RESTART_CHECK_PENDING");
  assertThat(more.addedNodeIds()).isEmpty();
  assertThat(movement.resume(SITE,id).decision()).isEqualTo("RESTART_CHECK_PENDING");
  assertThat(held(OHT1)).doesNotContain(103L);
 }

 @Test void l2_anOldStandingReportIsNotAPresentStandstill(){
  place(AGV1,203L,false); hold(AGV1,List.of(203L,204L)); begin();
  jdbc.update("UPDATE transport.restart_check SET started_at=now()-interval '2 hours' WHERE site_id=1 AND finished_at IS NULL");
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 hour' WHERE site_id=1 AND vehicle_id=?",AGV1);
  var st=check.state(SITE);
  assertThat(st.confirmable()).isFalse();
  assertThat(kinds(st)).contains("VEHICLE_REPORT_STALE");
  assertThatThrownBy(()->tx.execute(s->check.confirm(SITE,"stale","tester")))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("RESTART_CHECK_UNRESOLVED"));
  assertThat(held(AGV1)).containsExactly(203L,204L);
 }

 @Test void l3_aDeliveryWithNothingAboardGoesToRecoveryInsteadOfResuming(){
  long id=pickedUpOrder();
  begin();
  place(OHT1,101L,false);
  var st=check.state(SITE);
  assertThat(st.items()).anySatisfy(i->{ assertThat(i.kind()).isEqualTo("CARRIER_MISMATCH"); assertThat(i.orderId()).isEqualTo(id); });
  tx.execute(s->check.confirm(SITE,"compared after restart","tester"));
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(movement.resume(SITE,id).decision()).isNotEqualTo("RESUMED");
 }

 @Test void l3_aVehicleCarryingSomethingBeforePickupBlocksConfirmation(){
  String key="restart-gate-"+System.nanoTime();
  place(OHT1,101L,false);
  long id=orders.accept(SITE,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  assignment.assign(SITE,id,"tester",null);
  begin();
  place(OHT1,101L,true);
  var st=check.state(SITE);
  assertThat(st.items()).anySatisfy(i->{ assertThat(i.kind()).isEqualTo("CARRIER_UNEXPECTED"); assertThat(i.blocking()).isTrue(); });
  assertThat(st.confirmable()).isFalse();
 }

 @Test void l4_roadGrantedAgainAfterConfirmationGoesOutAsANewCommand(){
  long id=pickedUpOrder();
  var before=movement.resume(SITE,id);
  assertThat(before.decision()).isEqualTo("RESUMED");
  begin();
  place(OHT1,101L,true);
  tx.execute(s->check.confirm(SITE,"standing after restart","tester"));
  assertThat(status(before.commandId())).isEqualTo("WITHDRAWN");
  var after=movement.resume(SITE,id);
  assertThat(after.decision()).isEqualTo("RESUMED");
  assertThat(after.commandId()).isNotEqualTo(before.commandId());
  assertThat(status(after.commandId())).isEqualTo("PENDING");
 }
}
