package com.amhs;
import com.amhs.fleet.*;
import com.amhs.simulation.SimulatedVehicles;
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
 * AGV charging: a low battery asks to charge and the AGV is sent to a free charge point, charges and
 * is released; one that must charge takes no work; work wins over an ordinary request; a charge point
 * is never taken twice; an OHT never charges.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class ChargingSqlTest {
 @Autowired SimulatedVehicles simulator; @Autowired SiteSweep sweep; @Autowired TransportOrders orders;
 @Autowired TransportAssignment assignment; @Autowired TransportLedger ledger; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx; @Autowired ZoneEvacuation moves;
 private static final long SITE=1, REVISION=1, OHT1=1, AGV1=2, AGV2=4;

 @BeforeEach @AfterEach void clean(){
  simulator.clear();
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status NOT IN ('COMPLETED','CANCELLED','FAILED')");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("DELETE FROM transport.charge_request");
  jdbc.update("DELETE FROM transport.charge_session");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=FALSE,home_node_id=NULL WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET charging_policy='NEAREST',charge_node_id=CASE id WHEN 2 THEN 212 ELSE 205 END"
   +" WHERE site_id=? AND id IN (2,4)",SITE);
  jdbc.update("UPDATE topology.node SET charge_point=(id IN (205,212)) WHERE site_id=? AND revision_id=?",SITE,REVISION);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=FALSE,node_count=20,more_remaining_nodes=3,"
   +"more_min_remaining_nodes=1,run_mode='OPERATION',yield_after_seconds=5,home_return_seconds=0,"
   +"charge_request_pct=40,charge_critical_pct=20,charge_release_pct=80 WHERE site_id=?",SITE);
 }
 private void simulate(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id IN (2,4)) WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION',node_count=6,more_remaining_nodes=2,"
   +"more_min_remaining_nodes=0 WHERE site_id=?",SITE);
 }
 private VehicleSnapshot now(long id){ return fleet.find(SITE,REVISION,id).orElseThrow(); }
 private void tick(){ sweep.run(SITE,"test",null); simulator.settle(SITE); simulator.drive(SITE,1.0); }
 private Optional<Relocation> moveOf(long vehicleId){
  return ledger.openRelocations(SITE).stream().filter(r->r.vehicleId()==vehicleId).findFirst();
 }
 private int level(long vehicleId){ return ledger.chargeLevels(SITE).getOrDefault(vehicleId,0); }

 @Test void aLowBatteryGoesToTheNearestFreeChargePointChargesAndIsReleased(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  simulator.place(SITE,AGV1,203L);
  simulator.setBattery(SITE,AGV1,35);
  Long target=null; boolean charged=false;
  for(int i=0;i<150;i++){
   tick();
   var move=moveOf(AGV1);
   if(move.isPresent()&&move.get().purpose().equals(Relocation.CHARGE)) target=move.get().targetNodeId();
   if("CHARGING".equals(now(AGV1).status())) charged=true;
   if(charged&&"IDLE".equals(now(AGV1).status())) break;
  }
  // From 203 the nearest charge point is 212, ahead on the loop.
  assertThat(target).isEqualTo(212L);
  assertThat(charged).isTrue();
  assertThat(now(AGV1).nodeId()).isEqualTo(212L);
  assertThat(now(AGV1).status()).isEqualTo("IDLE");
  assertThat(now(AGV1).batteryPct()).isGreaterThan(80.0);
  assertThat(level(AGV1)).isZero();
 }

 @Test void anAgvThatMustChargeIsGivenNoWork(){
  simulate();
  simulator.place(SITE,AGV1,201L);
  simulator.place(SITE,AGV2,208L);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  simulator.setBattery(SITE,AGV1,15);
  sweep.run(SITE,"test",null);
  assertThat(level(AGV1)).isEqualTo(2);
  long id=tx.execute(s->orders.accept(SITE,new OrderRequest("CHG",UUID.randomUUID().toString(),"AGV-A","AGV-B",
   "CHG-"+UUID.randomUUID(),50),"test",null).order().id());
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,id,"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NO_AVAILABLE_VEHICLE"));
 }

 @Test void workWinsOverAnOrdinaryChargeRequest(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  simulator.place(SITE,AGV1,211L);
  simulator.setBattery(SITE,AGV1,35);
  for(int i=0;i<30&&!"CHARGING".equals(now(AGV1).status());i++) tick();
  assertThat(now(AGV1).status()).isEqualTo("CHARGING");
  assertThat(level(AGV1)).isEqualTo(1);
  // Work for an AGV arrives and no other AGV is free: the charge stops and the AGV takes the work.
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE WHERE site_id=?",SITE);
  long id=tx.execute(s->orders.accept(SITE,new OrderRequest("CHG",UUID.randomUUID().toString(),"AGV-A","AGV-B",
   "CHG-"+UUID.randomUUID(),50),"test",null).order().id());
  for(int i=0;i<10&&orders.find(SITE,id).orElseThrow().status()==OrderStatus.QUEUED;i++) tick();
  assertThat(orders.find(SITE,id).orElseThrow().status()).isNotEqualTo(OrderStatus.QUEUED);
  assertThat(ledger.assignedVehicleIds(SITE)).contains(AGV1);
  assertThat(level(AGV1)).isZero();
 }

 @Test void aChargePointIsNeverTakenByTwo(){
  simulate();
  // Both AGVs name 212 as their own charge node and 205 is not a charge point: one charger for two.
  jdbc.update("UPDATE fleet.vehicle_profile SET charge_node_id=212 WHERE site_id=? AND id IN (2,4)",SITE);
  jdbc.update("UPDATE topology.node SET charge_point=FALSE WHERE site_id=? AND revision_id=? AND id=205",SITE,REVISION);
  simulator.place(SITE,AGV1,210L);
  simulator.place(SITE,AGV2,203L);
  simulator.setBattery(SITE,AGV1,35);
  simulator.setBattery(SITE,AGV2,35);
  List<ZoneEvacuation.Step> steps=sweep.run(SITE,"test",null).evacuated();
  var targets=ledger.openRelocations(SITE).stream().filter(r->r.purpose().equals(Relocation.CHARGE))
   .map(Relocation::targetNodeId).toList();
  assertThat(targets).containsExactly(212L);
  assertThat(steps).anySatisfy(st->assertThat(st.decision()).isEqualTo("NO_FREE_CHARGER"));
 }

 @Test void anOhtNeverCharges(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT1);
  simulator.place(SITE,OHT1,101L);
  simulator.setBattery(SITE,OHT1,5);
  for(int i=0;i<3;i++) tick();
  assertThat(level(OHT1)).isZero();
  assertThat(moveOf(OHT1)).isEmpty();
 }

 private long agvOrder(){
  return tx.execute(s->orders.accept(SITE,new OrderRequest("CHG",UUID.randomUUID().toString(),"AGV-A","AGV-B",
   "CHG-"+UUID.randomUUID(),50),"test",null).order().id());
 }

 @Test void aBatteryJustBelowCriticalIsNotGivenWorkEvenBeforeItsLevelIsRecorded(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE WHERE site_id=?",SITE);
  simulator.place(SITE,AGV1,201L);
  simulator.setBattery(SITE,AGV1,15);
  long id=agvOrder();
  // The first pass dispatches before the charging part has recorded anything for AGV-01.
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.assigned()).doesNotContain(id);
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);
  assertThat(level(AGV1)).isEqualTo(2);
  assertThat(moveOf(AGV1)).hasValueSatisfying(r->assertThat(r.purpose()).isEqualTo(Relocation.CHARGE));
 }

 @Test void aVehicleToldToStartChargingIsNotGivenWork(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  simulator.place(SITE,AGV1,212L);
  simulator.setBattery(SITE,AGV1,35);
  // Standing on its charge point: the pass only writes CHARGE_START; the vehicle is still idle.
  sweep.run(SITE,"test",null);
  assertThat(now(AGV1).status()).isEqualTo("IDLE");
  assertThat(ledger.chargingVehicleIds(SITE)).contains(AGV1);
  long id=agvOrder();
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,id,"test",null)))
   .isInstanceOf(TransportRejected.class);
  assertThat(ledger.assignedVehicleIds(SITE)).doesNotContain(AGV1);
 }

 @Test void stoppingTheChargeKeepsTheControlModeTheVehicleIsIn(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  simulator.place(SITE,AGV1,212L);
  simulator.setBattery(SITE,AGV1,35);
  for(int i=0;i<5&&!"CHARGING".equals(now(AGV1).status());i++) tick();
  assertThat(now(AGV1).status()).isEqualTo("CHARGING");
  // Somebody takes it to manual while it charges; then it is charged.
  simulator.setState(SITE,AGV1,"MANUAL","CHARGING");
  simulator.setBattery(SITE,AGV1,85);
  for(int i=0;i<4;i++) tick();
  assertThat(now(AGV1).status()).isEqualTo("IDLE");
  assertThat(now(AGV1).mode()).isEqualTo("MANUAL");
 }
 @Test void aVehicleStartingToChargeIsNeitherSentHomeNorMovedAside(){
  simulate();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,AGV2);
  jdbc.update("UPDATE platform.site_operation SET home_return_seconds=1 WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=203 WHERE site_id=? AND id=?",SITE,AGV1);
  simulator.place(SITE,AGV1,212L);
  simulator.setBattery(SITE,AGV1,35);
  sweep.run(SITE,"test",null);
  assertThat(ledger.chargingVehicleIds(SITE)).contains(AGV1);
  // The start command has gone out and been accepted, but the vehicle still reports idle.
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status IN ('PENDING','DISPATCHING','SENT')");
  jdbc.update("UPDATE fleet.vehicle_state SET idle_since=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  jdbc.update("UPDATE transport.relocation SET finished_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
  jdbc.update("UPDATE transport.assignment SET released_at=now()-interval '1 hour' WHERE vehicle_id=? AND released_at>now()-interval '1 hour'",AGV1);
  assertThat(now(AGV1).status()).isEqualTo("IDLE");
  var home=tx.execute(s->moves.returnHome(SITE));
  assertThat(home).noneSatisfy(st->assertThat(st.vehicleId()).isEqualTo(AGV1));
  assertThat(moveOf(AGV1)).isEmpty();
 }
}
