package com.amhs;
import com.amhs.fleet.*;
import com.amhs.simulation.SimulatedLoad;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * A17: the simulated load writes work between ports one kind of vehicle reaches, never more open per
 * kind than that kind has vehicles in automatic work, and its watch notices two vehicles on one node.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.simulation.load.per-minute=600","amhs.simulation.load.seed=7","amhs.simulation.load.tick-ms=3600000"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class SimulatedLoadSqlTest {
 @Autowired SimulatedLoad load; @Autowired TransportOrders orders; @Autowired FleetState fleet; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, REVISION=1;

 @BeforeEach @AfterEach void clean(){
  jdbc.update("UPDATE transport.restart_check SET finished_at=now(),outcome='CONFIRMED',finished_by='test' WHERE finished_at IS NULL");
  jdbc.update("UPDATE platform.site_operation SET restart_check_since=NULL,sequence_running=TRUE,run_mode='OPERATION',auto_dispatch=FALSE WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM traffic.node_reservation WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }

 @Test void itWritesWorkUpToTheVehiclesOfEachKindAndWatchesTheFloor(){
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION' WHERE site_id=?",SITE);
  // One OHT and one AGV in automatic work: at most one open order of each kind.
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=(id IN (1,2)) WHERE site_id=? AND revision_id=?",SITE,REVISION);
  fleet.report(SITE,REVISION,1,new VehicleReport(101L,true,true,true,false,"SIMULATED"));
  fleet.report(SITE,REVISION,2,new VehicleReport(201L,true,true,true,false,"SIMULATED"));
  load.pass(SITE,1.0);   // ten due at 600 a minute
  var open=orders.listActive(SITE,100).stream().filter(o->"SIM_LOAD".equals(o.requestSource())).toList();
  assertThat(open).hasSizeBetween(1,2);
  assertThat(open.stream().map(TransportOrder::vehicleType).distinct().count()).isEqualTo(open.size());
  var report=load.report(SITE);
  assertThat(report.written()).isEqualTo(open.size());
  // The scheduled pass may also have looked; what matters is that it looked and saw nothing wrong.
  assertThat(report.samples()).isPositive();
  assertThat(report.sameNodeSamples()).isZero();
  // Two vehicles reported on one node is what must never be seen; the watch counts it.
  fleet.report(SITE,REVISION,3,new VehicleReport(101L,true,true,true,false,"SIMULATED"));
  load.pass(SITE,0.0);
  assertThat(load.report(SITE).sameNodeSamples()).isPositive();
 }
}
