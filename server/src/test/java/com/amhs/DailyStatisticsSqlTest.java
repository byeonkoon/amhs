package com.amhs;
import com.amhs.statistics.DailyStatistics;
import com.amhs.transport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

/**
 * A32: a site day is [00:00, next 00:00) in the site's zone, matched against when an order ended.
 * 8 completed, 1 cancelled after it started and 1 failed is 80%; cancels before anything started are
 * counted apart; a day with nothing to divide by has no rate; an order ending one millisecond before
 * local midnight belongs to that day, one ending at midnight to the next.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.site.zone=Asia/Seoul"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class DailyStatisticsSqlTest {
 @Autowired DailyStatistics statistics; @Autowired TransportOrders orders; @Autowired JdbcTemplate jdbc;

 private long template;
 /** An order ending at a given instant (written in UTC), as it would have been recorded. */
 private void ended(String status,boolean started,String reason,String terminalUtc){
  jdbc.update("INSERT INTO transport.order(site_id,revision_id,request_source,idempotency_key,request_fingerprint,source_port_id,"
   +"destination_port_id,vehicle_type,carrier_code,priority,status,created_at,started_at,terminal_at,cancel_reason)"
   +" SELECT o.site_id,o.revision_id,'TEST','stat-'||gen_random_uuid(),'test',o.source_port_id,o.destination_port_id,o.vehicle_type,"
   +"NULL,50,?,?::timestamptz-interval '10 minutes',CASE WHEN ? THEN ?::timestamptz-interval '5 minutes' END,?::timestamptz,?"
   +" FROM transport.order o WHERE o.id=?",status,terminalUtc,started,terminalUtc,terminalUtc,reason,template);
 }

 @Test @Transactional void aDayIsCountedInTheSiteZoneAndTheRateLeavesOutWhatNeverStarted(){
  String key="stat-template-"+System.nanoTime();
  template=orders.accept(1,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  // 2001-03-10 in Seoul is 2001-03-09T15:00Z .. 2001-03-10T15:00Z.
  for(int i=0;i<7;i++) ended("COMPLETED",true,null,"2001-03-10T03:00:00Z");
  ended("COMPLETED",true,null,"2001-03-10T14:59:59.999Z");        // 23:59:59.999 local: still the 10th
  ended("CANCELLED",true,"operator stop","2001-03-10T05:00:00Z");
  ended("FAILED",true,null,"2001-03-10T06:00:00Z");
  ended("CANCELLED",false,null,"2001-03-10T07:00:00Z");
  ended("CANCELLED",false,null,"2001-03-10T08:00:00Z");
  ended("COMPLETED",true,null,"2001-03-10T15:00:00Z");             // 00:00 local: the 11th
  var report=statistics.daily(1,LocalDate.of(2001,3,10),LocalDate.of(2001,3,12),null);
  assertThat(report.zone()).isEqualTo("Asia/Seoul");
  var d10=report.days().get(0); var d11=report.days().get(1); var d12=report.days().get(2);
  assertThat(d10.completed()).isEqualTo(8);
  assertThat(d10.cancelledAfterStart()).isEqualTo(1);
  assertThat(d10.failed()).isEqualTo(1);
  assertThat(d10.cancelledBeforeStart()).isEqualTo(2);
  assertThat(d10.normalRatePct()).isEqualTo(80.0);
  assertThat(d10.fromUtc().toString()).isEqualTo("2001-03-09T15:00Z");
  assertThat(d11.completed()).isEqualTo(1);
  assertThat(d11.normalRatePct()).isEqualTo(100.0);
  assertThat(d12.normalRatePct()).as("nothing to divide by").isNull();
  assertThat(report.cancelReasons()).anySatisfy(r->{ assertThat(r.reason()).isEqualTo("operator stop"); assertThat(r.afterStart()).isEqualTo(1); });
  assertThat(report.cancelReasons()).anySatisfy(r->{ assertThat(r.reason()).isEqualTo("(사유 없음)"); assertThat(r.beforeStart()).isEqualTo(2); });
  assertThat(report.abnormalTotal()).isEqualTo(2);
  assertThat(report.abnormal()).extracting(DailyStatistics.Abnormal::status).containsExactlyInAnyOrder("CANCELLED","FAILED");
 }
}
