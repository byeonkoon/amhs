package com.amhs;
import com.amhs.diagnostics.HostDiagnostics;
import com.amhs.diagnostics.HostDiagnostics.State;
import com.amhs.fleet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * A31: the host, its network and each vehicle's link, each value saying how well it is known. What
 * cannot be read carries no value -- never a zero or a healthy-looking one -- and a vehicle's link is
 * told apart as heard lately, heard too long ago, or never heard.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.diagnostics.host-id=test-host","amhs.diagnostics.reach=AP-TEST=127.0.0.1"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class HostDiagnosticsSqlTest {
 @Autowired HostDiagnostics diagnostics; @Autowired FleetState fleet; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, REVISION=1;

 @BeforeEach @AfterEach void clean(){ jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE); }

 @Test void eachValueSaysHowWellItIsKnownAndUnknownIsNeverAZero(){
  var report=diagnostics.report(SITE);
  assertThat(report.hostId()).isEqualTo("test-host");
  assertThat(report.host()).anySatisfy(m->{ assertThat(m.name()).isEqualTo("memory.total"); assertThat(m.state()).isEqualTo(State.KNOWN);
   assertThat(m.value()).isPositive(); });
  // Whatever is not KNOWN has no value to mistake for a reading.
  assertThat(report.host()).filteredOn(m->m.state()!=State.KNOWN).allSatisfy(m->assertThat(m.value()).isNull());
  assertThat(report.nics()).isNotEmpty();
  assertThat(report.nics()).allSatisfy(n->{ assertThat(n.speed().state()).isEqualTo(State.UNSUPPORTED); assertThat(n.speed().value()).isNull(); });
  assertThat(report.broker().state()).isEqualTo(State.UNSUPPORTED);
  // Named: unknown until it has been asked (the scheduled check may already have asked), never "reachable" by default.
  assertThat(report.accessPoints()).singleElement().satisfies(r->{
   assertThat(r.state()).isIn(State.UNKNOWN,State.KNOWN);
   if(r.state()==State.UNKNOWN) assertThat(r.millis()).isNull();
  });
  diagnostics.probeAccessPoints();
  assertThat(diagnostics.report(SITE).accessPoints()).singleElement().satisfies(r->assertThat(r.state()).isEqualTo(State.KNOWN));
 }

 @Test void aVehicleLinkIsHeardLatelyHeardTooLongAgoOrNeverHeard(){
  fleet.report(SITE,REVISION,1,new VehicleReport(101L,true,true,true,false,"SIMULATED"));
  fleet.report(SITE,REVISION,2,new VehicleReport(201L,true,true,true,false,"SIMULATED"));
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=2",SITE);
  var links=diagnostics.report(SITE).vehicles();
  assertThat(links).anySatisfy(l->{ assertThat(l.vehicleId()).isEqualTo(1); assertThat(l.state()).isEqualTo(State.KNOWN); });
  assertThat(links).anySatisfy(l->{ assertThat(l.vehicleId()).isEqualTo(2); assertThat(l.state()).isEqualTo(State.STALE);
   assertThat(l.ageSeconds()).isGreaterThan(3000L); });
  assertThat(links).anySatisfy(l->{ assertThat(l.vehicleId()).isEqualTo(3); assertThat(l.state()).isEqualTo(State.UNKNOWN);
   assertThat(l.lastReport()).isNull(); });
 }
}
