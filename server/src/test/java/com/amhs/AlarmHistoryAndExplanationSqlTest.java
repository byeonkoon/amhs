package com.amhs;
import com.amhs.alarm.SiteAlarms;
import com.amhs.fleet.*;
import com.amhs.traffic.TrafficControl;
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
 * A15: an alarm's coming and going is kept, with who looked at it and what it was about; and an
 * order says why it is where it is -- the reason it was last passed over and since when, and what its
 * vehicle is waiting for on the road.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class AlarmHistoryAndExplanationSqlTest {
 @Autowired SiteAlarms alarms; @Autowired FleetState fleet; @Autowired TransportOrders orders;
 @Autowired TransportAssignment assignment; @Autowired SiteSweep sweep; @Autowired TrafficControl traffic;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx; @Autowired RestartCheck restartCheck;
 private static final long SITE=1, REVISION=1, OHT1=1, OHT2=3;

 @BeforeEach void clean(){
  jdbc.update("UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='test' WHERE finished_at IS NULL");
  jdbc.update("UPDATE platform.site_operation SET restart_check_since=NULL,sequence_running=TRUE,node_count=20,auto_dispatch=FALSE WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("UPDATE transport.late_evidence SET resolved_at=now(),resolved_by='test',resolution='test' WHERE resolved_at IS NULL");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.alarm_occurrence SET cleared_at=now() WHERE cleared_at IS NULL");
  jdbc.update("DELETE FROM platform.alarm_acknowledgement WHERE site_id=?",SITE);
 }
 @AfterEach void restore(){
  clean();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }

 private void report(long vehicle,long node,String status){
  fleet.report(SITE,REVISION,vehicle,new VehicleReport(node,true,true,!"ERROR".equals(status),false,"SIMULATED",null,"REMOTE",status));
 }
 private SiteAlarms.Occurrence occurrence(String key){
  return alarms.history(SITE,null,null,500).stream().filter(o->o.key().equals(key)).findFirst().orElse(null);
 }

 @Test void anAlarmIsKeptFromWhenItWasSeenUntilItWentWithWhoLookedAndWhatItWasAbout(){
  report(OHT1,101L,"ERROR");
  String key="VEHICLE_ERROR:"+OHT1;
  tx.executeWithoutResult(s->alarms.observe(SITE));
  var opened=occurrence(key);
  assertThat(opened).isNotNull();
  assertThat(opened.clearedAt()).isNull();
  assertThat(opened.vehicleIds()).containsExactly(OHT1);
  assertThat(alarms.alarms(SITE)).anySatisfy(a->{ assertThat(a.key()).isEqualTo(key); assertThat(a.about().vehicleIds()).containsExactly(OHT1); });

  tx.executeWithoutResult(s->alarms.acknowledge(SITE,key,"on the way to it","tester"));
  tx.executeWithoutResult(s->alarms.observe(SITE));
  // Still the same occurrence, now saying who looked.
  var seen=occurrence(key);
  assertThat(seen.id()).isEqualTo(opened.id());
  assertThat(seen.ackReason()).isEqualTo("on the way to it");
  assertThat(seen.ackActor()).isEqualTo("tester");

  report(OHT1,101L,"IDLE");
  tx.executeWithoutResult(s->{ alarms.observe(SITE); alarms.retireAcknowledgementsOfEndedConditions(SITE); });
  var gone=occurrence(key);
  assertThat(gone.clearedAt()).isNotNull();
  // The acknowledgement itself is forgotten with the condition; the record of it is not.
  assertThat(gone.ackReason()).isEqualTo("on the way to it");
  assertThat(alarms.history(SITE,OHT1,null,50)).anySatisfy(o->assertThat(o.id()).isEqualTo(opened.id()));
  assertThat(alarms.history(SITE,OHT2,null,50)).noneSatisfy(o->assertThat(o.id()).isEqualTo(opened.id()));

  // Coming back is a new occurrence, not the old one reopened.
  report(OHT1,101L,"ERROR");
  tx.executeWithoutResult(s->alarms.observe(SITE));
  var again=occurrence(key);
  assertThat(again.id()).isNotEqualTo(opened.id());
  assertThat(again.ackReason()).isNull();
 }

 @Test void aWaitingOrderSaysWhyItWasPassedOverAndSinceWhen(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE WHERE site_id=?",SITE);
  String key="explain-"+System.nanoTime();
  long id=orders.accept(SITE,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  sweep.run(SITE,"test",null);
  var first=orders.explain(SITE,id);
  assertThat(first.order().status()).isEqualTo(OrderStatus.QUEUED);
  assertThat(first.dispatch()).isNotNull();
  assertThat(first.dispatch().reason()).isNotBlank();
  sweep.run(SITE,"test",null);
  // The same reason again: since when is still when it began.
  assertThat(orders.explain(SITE,id).dispatch().since()).isEqualTo(first.dispatch().since());
 }

 @Test void anAssignedOrderSaysWhatItsVehicleIsWaitingForOnTheRoad(){
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT1);
  report(OHT1,101L,"IDLE");
  report(OHT2,103L,"IDLE");
  tx.execute(s->traffic.request(SITE,REVISION,OHT2,null,List.of(103L)));
  String key="explain-"+System.nanoTime();
  long id=orders.accept(SITE,new OrderRequest("TEST",key,"OHT-B","OHT-C",key,50),"tester",null).order().id();
  assignment.assign(SITE,id,"tester",null);
  // Its vehicle asks for the road through 103, where OHT-02 stands.
  tx.execute(s->traffic.request(SITE,REVISION,OHT1,id,List.of(101L,102L,103L,104L)));
  var why=orders.explain(SITE,id);
  assertThat(why.vehicleId()).isEqualTo(OHT1);
  assertThat(why.roadWait()).isNotNull();
  assertThat(why.roadWait().blockerId()).isEqualTo(OHT2);
  assertThat(why.roadWait().wantedNodeId()).isEqualTo(103L);
 }

 @Test void aSiteHeldForItsRestartCheckIsAnAlarm(){
  long agv=2;
  report(agv,203L,"IDLE");
  tx.execute(s->traffic.request(SITE,REVISION,agv,null,List.of(203L,204L)));
  var check=tx.execute(s->restartCheck.begin(SITE));
  assertThat(check.pending()).isTrue();
  assertThat(alarms.alarms(SITE)).anySatisfy(a->{
   assertThat(a.key()).isEqualTo("RESTART_CHECK:"+check.checkId());
   assertThat(a.severity()).isEqualTo(com.amhs.alarm.Alarm.Severity.CRITICAL);
  });
 }
}
