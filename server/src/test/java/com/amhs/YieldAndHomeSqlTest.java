package com.amhs;
import com.amhs.fleet.*;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A vehicle with nothing to do gives way to one that has been waiting for where it stands, and a
 * vehicle without work for a while goes home.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class YieldAndHomeSqlTest {
 @Autowired SimulatedVehicles simulator; @Autowired SiteSweep sweep; @Autowired ZoneEvacuation moves;
 @Autowired TransportLedger ledger; @Autowired TrafficControl traffic; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2, AGV2=4;

 @BeforeEach @AfterEach void clean(){
  simulator.clear();
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=FALSE,home_node_id=NULL WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=FALSE,node_count=20,more_remaining_nodes=3,"
   +"more_min_remaining_nodes=1,run_mode='OPERATION',yield_after_seconds=5,home_return_seconds=0 WHERE site_id=?",SITE);
 }
 private void simulate(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id IN (2,4)) WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION',node_count=6,more_remaining_nodes=2,"
   +"more_min_remaining_nodes=0 WHERE site_id=?",SITE);
 }
 private VehicleSnapshot now(long id){ return fleet.find(SITE,REVISION,id).orElseThrow(); }
 private void tick(){
  // Waiting is measured in wall time; the test makes it long without sleeping.
  jdbc.update("UPDATE traffic.reservation_wait SET since=now()-interval '1 minute' WHERE site_id=?",SITE);
  sweep.run(SITE,"test",null);
  simulator.settle(SITE);
  simulator.drive(SITE,1.0);
 }
 private List<ZoneEvacuation.Step> home(){ return tx.execute(s->moves.returnHome(SITE)); }
 private Optional<Relocation> moveOf(long vehicleId){
  return ledger.openRelocations(SITE).stream().filter(r->r.vehicleId()==vehicleId).findFirst();
 }

 @Test void anIdleVehicleInTheWayStepsAsideAndTheWaitingOneGetsThrough(){
  simulate();
  simulator.place(SITE,AGV2,212L);
  simulator.place(SITE,AGV1,202L);
  // AGV-02 is sent through 201 and 202 to 203; AGV-01 stands on 202 with nothing to do.
  tx.execute(s->moves.moveTo(SITE,AGV2,203L));
  boolean yielded=false;
  for(int i=0;i<60&&now(AGV2).nodeId()!=203L;i++){
   tick();
   var aside=moveOf(AGV1);
   if(aside.isPresent()){
    yielded=true;
    assertThat(aside.get().purpose()).isEqualTo(Relocation.YIELD);
    // Not onto the road the waiting vehicle still has to drive.
    assertThat(aside.get().targetNodeId()).isNotIn(201L,202L,203L);
   }
  }
  assertThat(yielded).isTrue();
  assertThat(now(AGV2).nodeId()).isEqualTo(203L);
  assertThat(now(AGV1).nodeId()).isNotEqualTo(202L);
 }

 @Test void aVehicleThatMayNotBeMovedOnItsOwnIsLeftWhereItIs(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV1);
  simulator.place(SITE,AGV2,212L);
  simulator.place(SITE,AGV1,202L);
  tx.execute(s->moves.moveTo(SITE,AGV2,203L));
  for(int i=0;i<12;i++) tick();
  assertThat(moveOf(AGV1)).isEmpty();
  assertThat(now(AGV1).nodeId()).isEqualTo(202L);
  // The step says why, so an operator can see what is holding the road.
  jdbc.update("UPDATE traffic.reservation_wait SET since=now()-interval '1 minute' WHERE site_id=?",SITE);
  List<ZoneEvacuation.Step> steps=tx.execute(s->moves.yieldAll(SITE));
  assertThat(steps).anySatisfy(st->{
   assertThat(st.vehicleId()).isEqualTo(AGV1);
   assertThat(st.decision()).isEqualTo("YIELD_ASSIGNMENT_DISABLED");
  });
 }

 @Test void aVehicleWithoutWorkGoesHomeWhenHomeIsFree(){
  simulate();
  jdbc.update("UPDATE platform.site_operation SET home_return_seconds=1 WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=212 WHERE site_id=? AND id=?",SITE,AGV1);
  // Its last work ended a while ago (the clean-up above closed old records just now).
  jdbc.update("UPDATE transport.relocation SET finished_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  jdbc.update("UPDATE transport.assignment SET released_at=now()-interval '1 hour' WHERE vehicle_id=? AND released_at>now()-interval '1 hour'",AGV1);
  simulator.place(SITE,AGV1,203L);
  // It has stood idle there for a minute already.
  jdbc.update("UPDATE fleet.vehicle_state SET idle_since=now()-interval '1 minute' WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  boolean sent=false;
  for(int i=0;i<40&&now(AGV1).nodeId()!=212L;i++){
   tick();
   sent|=moveOf(AGV1).map(r->r.purpose().equals(Relocation.HOME)).orElse(false);
  }
  assertThat(sent).isTrue();
  assertThat(now(AGV1).nodeId()).isEqualTo(212L);
  tick();
  assertThat(moveOf(AGV1)).isEmpty();
 }

 @Test void aVehicleCarryingSomethingOrFindingItsHomeTakenStays(){
  simulate();
  jdbc.update("UPDATE platform.site_operation SET home_return_seconds=1 WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=212 WHERE site_id=? AND id=?",SITE,AGV1);
  simulator.place(SITE,AGV1,203L);
  simulator.place(SITE,AGV2,212L);
  // AGV-02 stands on AGV-01's home.
  assertThat(home()).isEmpty();
  assertThat(moveOf(AGV1)).isEmpty();
  // Home free, but a carrier on board: it stays where it is.
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,AGV2);
  simulator.clear();
  fleet.report(SITE,REVISION,AGV1,new VehicleReport(203L,true,true,true,true,"SIMULATED"));
  assertThat(home()).isEmpty();
  assertThat(moveOf(AGV1)).isEmpty();
 }

 @Test void aVehicleWithNoHistoryStillWaitsTheHomeDelay(){
  simulate();
  jdbc.update("UPDATE platform.site_operation SET home_return_seconds=3600 WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=212 WHERE site_id=? AND id=?",SITE,AGV1);
  jdbc.update("DELETE FROM transport.relocation WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  jdbc.update("UPDATE transport.assignment SET released_at=now()-interval '2 hour' WHERE vehicle_id=? AND released_at>now()-interval '2 hour'",AGV1);
  simulator.place(SITE,AGV1,203L);
  // Just became idle: an hour has not passed, so it stays.
  assertThat(home()).isEmpty();
  assertThat(moveOf(AGV1)).isEmpty();
  // Idle for two hours: now it goes home.
  jdbc.update("UPDATE fleet.vehicle_state SET idle_since=now()-interval '2 hour' WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  assertThat(home()).anySatisfy(st->assertThat(st.vehicleId()).isEqualTo(AGV1));
  assertThat(moveOf(AGV1)).hasValueSatisfying(r->assertThat(r.purpose()).isEqualTo(Relocation.HOME));
 }
}
