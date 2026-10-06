package com.amhs;
import com.amhs.overview.SiteOverview;
import com.amhs.transport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/**
 * Review L7 of b2b48d4, as the behaviour wanted: the overview counts every unfinished order. An old
 * order waiting for a person is not pushed out of the count by a thousand newer ones in the queue,
 * and the longest wait is the oldest queued order's, however many came after it.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class OverviewTotalsSqlTest {
 @Autowired SiteOverview overview; @Autowired TransportOrders orders; @Autowired JdbcTemplate jdbc;

 @Test @Transactional void anOldRecoveryIsCountedBehindAThousandNewerQueuedOrders(){
  jdbc.update("UPDATE transport.order SET status='CANCELLED' WHERE site_id=1 AND status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  String key="overview-total-"+System.nanoTime();
  long template=orders.accept(1,new OrderRequest("TEST",key,"OHT-A","OHT-C",key,50),"tester",null).order().id();
  jdbc.update("UPDATE transport.order SET status='RECOVERY_REQUIRED',created_at=now()-interval '3 hours' WHERE id=?",template);
  jdbc.update("INSERT INTO transport.order(site_id,revision_id,request_source,idempotency_key,request_fingerprint,source_port_id,"
   +"destination_port_id,vehicle_type,carrier_code,priority,status,created_at)"
   +" SELECT o.site_id,o.revision_id,'TEST',?||g,'test',o.source_port_id,o.destination_port_id,o.vehicle_type,?||g,50,'QUEUED',"
   +" now()-(g||' seconds')::interval FROM transport.order o CROSS JOIN generate_series(1,1001) g WHERE o.id=?",key,key,template);
  var work=overview.overview(1,60).work();
  assertThat(work.queued()).isEqualTo(1001);
  assertThat(work.needingAPerson()).isEqualTo(1);
  assertThat(work.oldestQueuedSeconds()).isGreaterThanOrEqualTo(1000L);
 }
}
