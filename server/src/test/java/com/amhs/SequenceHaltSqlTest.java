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
 @Autowired com.amhs.fleet.FleetState fleet;
 @Autowired JdbcTemplate jdbc;
 @Autowired com.amhs.integration.DeviceGateway gateway;
 @Autowired com.amhs.integration.CommandOutbox outbox;
 private static final long SITE=1, OHT=1;

 @BeforeEach @AfterEach void runningAndQuiet(){
  simulator.clear();
  jdbc.update("UPDATE platform.site_operation SET sequence_running=TRUE WHERE site_id=?",SITE);
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE order_id IN (SELECT id FROM transport.order WHERE site_id=?) AND released_at IS NULL",SITE);
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

 /**
  * A stopped site comes to rest on nodes, not between them.
  *
  * <p>A vehicle frozen between two nodes still holds the road it was given, and nothing can release
  * it while the site is stopped -- the map could then never be changed, and the site would be in a
  * state it could not leave. So what stops is the granting; what is already rolling runs out and
  * stands at the end of it, which is what stopping a plant means.
  */
 @Test void aStoppedSiteComesToRestOnNodes(){
  simulator.place(SITE,OHT,101L);
  long id=accept();
  for(int i=0;i<12;i++){ sweep.run(SITE,"test",null); simulator.settle(SITE); simulator.drive(SITE,0.4); }
  options.setSequenceRunning(SITE,false);
  // Only the motion keeps running while stopped, as the loop does.
  for(int i=0;i<40;i++) simulator.drive(SITE,1.0);

  var vehicle=fleet.find(SITE,1,OHT).orElseThrow();
  assertThat(vehicle.betweenNodes()).as("standing on a node, not between two").isFalse();
  assertThat(vehicle.nodeId()).isNotNull();
  assertThat(orders.find(SITE,id).orElseThrow().status().isTerminal())
   .as("and no further work was taken on").isFalse();

  // And the moves the controller had started are closed, instead of staying open for the length of
  // the stop: a site whose moves never close is one whose map can never be changed.
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).isEmpty();
 }

 @Test void aDeliveredMoveDrainsAndAcknowledgesWithoutHandoverWhileStopped(){
  simulator.place(SITE,OHT,101L);
  moves.moveTo(SITE,OHT,102L);
  assertThat(gateway.publishPending(SITE).delivered()).isPositive();
  options.setSequenceRunning(SITE,false);
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).hasSize(1);
  simulator.acknowledge(SITE);
  for(int i=0;i<40;i++) simulator.drive(SITE,1.0);
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().nodeId()).isEqualTo(102L);
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).isEmpty();
 }

 @Test void aStopWithdrawsAMoveThatNeverLeft(){
  simulator.place(SITE,OHT,101L);
  moves.moveTo(SITE,OHT,102L);
  options.setSequenceRunning(SITE,false);
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).isEmpty();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.command_outbox WHERE site_id=?"
   +" AND vehicle_id=? AND status='PENDING'",Integer.class,SITE,OHT)).isZero();
 }

 @Test void aStopNeedsAFreshStandingReportAfterItsDecision(){
  simulator.place(SITE,OHT,101L);
  moves.moveTo(SITE,OHT,102L);
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE site_id=? AND vehicle_id=? AND status='PENDING'",SITE,OHT);
  options.setSequenceRunning(SITE,false);
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).hasSize(1);
  // At a node is not proof of standing: arrival reports may still say MOVING.
  fleet.reportModeAndStatus(SITE,1,OHT,"REMOTE","MOVING");
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).hasSize(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation WHERE site_id=? AND vehicle_id=? AND node_id=102 AND released_at IS NULL",Integer.class,SITE,OHT)).isPositive();
  fleet.reportModeAndStatus(SITE,1,OHT,"REMOTE","IDLE");
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).hasSize(1);
  fleet.reportModeAndStatus(SITE,1,OHT,"REMOTE","IDLE");
  sweep.run(SITE,"test",null);
  assertThat(ledger.openRelocations(SITE)).isEmpty();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation WHERE site_id=? AND vehicle_id=? AND node_id=102 AND released_at IS NULL",Integer.class,SITE,OHT)).isZero();
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
