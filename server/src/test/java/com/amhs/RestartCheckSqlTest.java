package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.CommandOutbox;
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
 * After a server start the site does not progress until the ledger has been compared with the
 * vehicles: nothing in progress needs no check; otherwise every vehicle the work involves must have
 * reported since the start, on the road it holds, and no command may be left whose outcome the
 * restart lost; then an operator confirms with a reason. Only then do vehicles seen standing give
 * back the road ahead of them, and unsent commands from before the start are withdrawn.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class RestartCheckSqlTest {
 @Autowired RestartCheck check; @Autowired SiteSweep sweep; @Autowired TransportAssignment assignment;
 @Autowired ZoneEvacuation moves; @Autowired TrafficControl traffic; @Autowired FleetState fleet;
 @Autowired CommandOutbox outbox; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2;

 @BeforeEach @AfterEach void clean(){
  jdbc.update("UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='test' WHERE finished_at IS NULL");
  jdbc.update("UPDATE traffic.deadlock_recovery SET status='ABANDONED',finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM transport.charge_session");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20,sequence_running=TRUE,restart_check_since=NULL WHERE site_id=?",SITE);
 }

 private void place(long vehicle,long node){
  fleet.report(SITE,REVISION,vehicle,new VehicleReport(node,true,true,true,false,"SIMULATED"));
 }
 private void hold(long vehicle,List<Long> route){ tx.execute(s->traffic.request(SITE,REVISION,vehicle,null,route)); }
 private List<Long> held(long vehicle){ return traffic.heldBy(SITE,vehicle).stream().map(NodeHold::nodeId).toList(); }
 private String command(String id,String type){
  tx.executeWithoutResult(s->outbox.enqueueForVehicle(SITE,AGV1,id,type,"{}"));
  return id;
 }
 private String status(String commandId){
  return jdbc.queryForObject("SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,commandId);
 }
 private RestartCheck.State begin(){ return tx.execute(s->check.begin(SITE)); }
 private List<String> kinds(RestartCheck.State st){ return st.items().stream().map(RestartCheck.Item::kind).toList(); }
 private void rejectedWith(String code,ThrowingCallable call){
  assertThatThrownBy(call).isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo(code));
 }
 interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {}

 @Test void withNothingInProgressThereIsNothingToCompare(){
  place(AGV1,203L);
  hold(AGV1,List.of(203L));
  var st=begin();
  assertThat(st.pending()).isFalse();
  assertThat(st.last().outcome()).isEqualTo("NOTHING_IN_PROGRESS");
  assertThat(sweep.progressing(SITE)).isTrue();
 }

 @Test void workInProgressWaitsForEveryVehicleToReportAndForAnOperatorToConfirm() throws Exception {
  place(AGV1,203L);
  hold(AGV1,List.of(203L,204L,207L));
  String sent=command("restart-test-sent-"+System.nanoTime(),"MOVE");
  jdbc.update("UPDATE integration.command_outbox SET status='SENT',published_at=now() WHERE command_id=?",sent);
  String unsent=command("restart-test-unsent-"+System.nanoTime(),"MOVE");
  Thread.sleep(20);

  var st=begin();
  assertThat(st.pending()).isTrue();
  assertThat(kinds(st)).contains("VEHICLE_SILENT","COMMAND_IN_DOUBT","COMMAND_UNSENT");
  assertThat(st.confirmable()).isFalse();
  // Nothing progresses: no pass, no assignment, no manual move, nothing sent.
  assertThat(sweep.progressing(SITE)).isFalse();
  sweep.run(SITE,"test",null);
  assertThat(status(unsent)).isEqualTo("PENDING");
  rejectedWith("RESTART_CHECK_PENDING",()->tx.execute(s->assignment.assign(SITE,999_999L,"test",null)));
  rejectedWith("RESTART_CHECK_PENDING",()->tx.execute(s->moves.moveTo(SITE,AGV1,210L)));

  rejectedWith("RESTART_CHECK_UNRESOLVED",()->tx.execute(s->check.confirm(SITE,"looked","tester")));
  // Silence proves nothing: the command cannot be closed before the vehicle is seen standing.
  rejectedWith("VEHICLE_NOT_SEEN_STANDING",()->tx.execute(s->check.closeInDoubt(SITE,sent,"checked")));

  place(AGV1,203L);
  st=check.state(SITE);
  assertThat(kinds(st)).contains("VEHICLE_CONFIRMED").doesNotContain("VEHICLE_SILENT");
  st=tx.execute(s->check.closeInDoubt(SITE,sent,"vehicle stands at 203, display shows no job"));
  assertThat(status(sent)).isEqualTo("ACCEPTED");
  assertThat(jdbc.queryForObject("SELECT last_outcome FROM integration.command_outbox WHERE command_id=?",String.class,sent))
   .isEqualTo("CLOSED_AFTER_RESTART");
  assertThat(st.confirmable()).isTrue();

  rejectedWith("REASON_REQUIRED",()->tx.execute(s->check.confirm(SITE," ","tester")));
  st=tx.execute(s->check.confirm(SITE,"compared ledger with the floor","tester"));
  assertThat(st.pending()).isFalse();
  assertThat(st.last().outcome()).isEqualTo("CONFIRMED");
  assertThat(st.last().finishedBy()).isEqualTo("tester");
  assertThat(st.last().releasedNodes()).isEqualTo(2);
  assertThat(st.last().withdrawnCommands()).isEqualTo(1);
  assertThat(st.last().settledCommands()).isEqualTo(1);
  // Standing at 203: the road ahead goes back, its work is given road again from there.
  assertThat(held(AGV1)).containsExactly(203L);
  assertThat(status(unsent)).isEqualTo("WITHDRAWN");
  assertThat(sweep.progressing(SITE)).isTrue();
 }

 @Test void aVehicleFoundOffItsRoadBlocksUntilSomebodyIsolatesIt(){
  place(AGV1,203L);
  hold(AGV1,List.of(203L,204L));
  begin();
  // It reports from somewhere it was never given.
  place(AGV1,210L);
  var st=check.state(SITE);
  assertThat(st.items()).anySatisfy(i->{
   assertThat(i.kind()).isEqualTo("VEHICLE_OFF_ROAD"); assertThat(i.blocking()).isTrue(); assertThat(i.nodeId()).isEqualTo(210L);
  });
  rejectedWith("RESTART_CHECK_UNRESOLVED",()->tx.execute(s->check.confirm(SITE,"looked","tester")));
  fleet.takeOutOfService(SITE,REVISION,AGV1,"found off its road after restart","tester");
  st=check.state(SITE);
  assertThat(kinds(st)).contains("VEHICLE_ISOLATED").doesNotContain("VEHICLE_OFF_ROAD");
  st=tx.execute(s->check.confirm(SITE,"AGV-01 isolated for inspection","tester"));
  assertThat(st.pending()).isFalse();
  // Its road is not given back on a position it was never given.
  assertThat(held(AGV1)).containsExactly(203L,204L);
 }

 @Test void aStopKeptThroughTheCheckKeepsTheRoadWithIt(){
  place(AGV1,203L);
  hold(AGV1,List.of(203L,204L,207L));
  String stop=command("restart-test-stop-"+System.nanoTime(),"STOP");
  var st=begin();
  assertThat(st.pending()).isTrue();
  place(AGV1,203L);
  st=check.state(SITE);
  // The road is not promised back while a command that confirmation keeps is still outstanding.
  assertThat(st.items()).anySatisfy(i->{
   assertThat(i.kind()).isEqualTo("VEHICLE_CONFIRMED"); assertThat(i.detail()).isEqualTo("ROAD_AHEAD_KEPT_FOR_COMMAND");
  });
  st=tx.execute(s->check.confirm(SITE,"stop still to go out","tester"));
  assertThat(st.pending()).isFalse();
  assertThat(st.last().releasedNodes()).isZero();
  assertThat(st.last().withdrawnCommands()).isZero();
  assertThat(status(stop)).isEqualTo("PENDING");
  assertThat(held(AGV1)).containsExactly(203L,204L,207L);
 }

 @Test void aSiteWithNoMapInUseHasNothingToCompare(){
  long other=9_001L;
  jdbc.update("INSERT INTO platform.site(id,code,name) VALUES(?,?,?)",other,"RESTART-TEST","재시작 대조 시험");
  jdbc.update("INSERT INTO platform.site_operation(site_id,mode) VALUES(?,'MAINTENANCE')",other);
  try{
   // No map in use: there is no track to stand on, so nothing could have been under way. It must
   // not throw -- the whole server comes up through this call.
   var st=tx.execute(s->check.begin(other));
   assertThat(st.pending()).isFalse();
   assertThat(st.last().outcome()).isEqualTo("NOTHING_IN_PROGRESS");
   assertThat(jdbc.queryForObject("SELECT restart_check_since FROM platform.site_operation WHERE site_id=?",
    java.time.OffsetDateTime.class,other)).isNull();
  }finally{
   jdbc.update("DELETE FROM transport.restart_check WHERE site_id=?",other);
   jdbc.update("DELETE FROM platform.site_operation WHERE site_id=?",other);
   jdbc.update("DELETE FROM platform.site WHERE id=?",other);
  }
 }

 @Test void aStartDuringAnOpenCheckCountsOnlyReportsFromTheNewStart() throws Exception {
  place(AGV1,203L);
  hold(AGV1,List.of(203L,204L));
  var first=begin();
  place(AGV1,203L);
  assertThat(kinds(check.state(SITE))).contains("VEHICLE_CONFIRMED");
  Thread.sleep(20);
  var second=begin();
  assertThat(second.checkId()).isEqualTo(first.checkId());
  assertThat(second.since()).isAfter(first.since());
  assertThat(kinds(second)).contains("VEHICLE_SILENT");
 }
}
