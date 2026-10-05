package com.amhs;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Stopping the fleet: work is still accepted, and nothing progresses.
 *
 * <p>The three switches next to this one answer different questions -- simulated or real vehicles,
 * configuration writable or not, one vehicle normally dispatched or not. None of them answers the
 * one somebody reaches for when something is wrong on the floor: stop the plant, and let me look.
 *
 * <p>Accepting work while stopped is deliberate. The orders a shift has to do exist whether or not
 * the controller is running them; refusing to write them down would only move the queue onto paper.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class SequenceHaltSqlTest {
 @Autowired OperationOptionConfiguration options; @Autowired SiteSweep sweep;
 @Autowired TransportOrders orders; @Autowired SimulatedVehicles simulator;
 @Autowired ZoneEvacuation moves; @Autowired TransportLedger ledger;
 @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, OHT=1;

 @BeforeEach @AfterEach void runningAndQuiet(){
  simulator.clear();
  jdbc.update("UPDATE platform.site_operation SET sequence_running=TRUE WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE site_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",SITE);
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE site_id=? AND finished_at IS NULL",SITE);
  jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason='TEST' WHERE site_id=? AND released_at IS NULL",SITE);
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=?",SITE);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE,run_mode='SIMULATION' WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?) WHERE site_id=?",OHT,SITE);
 }
 @AfterEach void backToOperation(){
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=FALSE,run_mode='OPERATION' WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }
 private long accept(){
  return orders.accept(SITE,new OrderRequest("HALT",UUID.randomUUID().toString(),"OHT-A","OHT-C",
   UUID.randomUUID().toString(),50),"test",null).order().id();
 }

 @Test void aStoppedSiteTakesWorkAndProgressesNothing(){
  simulator.place(SITE,OHT,101L);
  assertThat(options.setSequenceRunning(SITE,false)).isFalse();
  assertThat(options.sequenceRunning(SITE)).isFalse();

  // Accepted: the work exists whether or not the controller is running it.
  long id=accept();
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);

  // And nothing moves it, pass after pass.
  for(int i=0;i<5;i++){
   var pass=sweep.run(SITE,"test",null);
   assertThat(pass.running()).isFalse();
   assertThat(pass.assigned()).isEmpty();
   assertThat(pass.published().claimed()).isZero();
  }
  assertThat(orders.find(SITE,id).orElseThrow().status()).as("still waiting").isEqualTo(OrderStatus.QUEUED);
  assertThat(ledger.assignedVehicleIds(SITE)).isEmpty();

  // Started again, the same queue is worked.
  assertThat(options.setSequenceRunning(SITE,true)).isTrue();
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.running()).isTrue();
  assertThat(pass.assigned()).contains(id);
 }

 /**
  * A stopped site sends nothing a person asks for by hand either.
  *
  * <p>The alternative is worse than refusing: the command would be written, sit unsent because
  * nothing is being published, and look like a vehicle about to move.
  */
 @Test void byHandIsRefusedWhileStopped(){
  simulator.place(SITE,OHT,101L);
  options.setSequenceRunning(SITE,false);
  assertThatThrownBy(()->moves.moveTo(SITE,OHT,102L))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("SEQUENCE_STOPPED"));
  assertThatThrownBy(()->moves.chargeNow(SITE,OHT,true))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("SEQUENCE_STOPPED"));
 }

 /** Stopping is not a configuration change: the moment it is wanted is while the site is running. */
 @Test void stoppingDoesNotAskForMaintenance(){
  String mode=jdbc.queryForObject("SELECT mode FROM platform.site_operation WHERE site_id=?",String.class,SITE);
  try{
   jdbc.update("UPDATE platform.site_operation SET mode='RUNNING' WHERE site_id=?",SITE);
   assertThat(options.setSequenceRunning(SITE,false)).isFalse();
   assertThat(options.setSequenceRunning(SITE,true)).isTrue();
  }finally{
   jdbc.update("UPDATE platform.site_operation SET mode=? WHERE site_id=?",mode,SITE);
  }
 }
}
