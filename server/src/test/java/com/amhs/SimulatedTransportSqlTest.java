package com.amhs;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * One OHT, simulated inside the server, carries one order from acceptance to completion with no
 * person involved: dispatch, the drive to the pickup, a pickup the load sensor confirms, the drive
 * on, a drop the sensor confirms, and the road given back.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class SimulatedTransportSqlTest {
 @Autowired SimulatedVehicles simulator; @Autowired SiteSweep sweep; @Autowired TransportOrders orders;
 @Autowired TrafficControl traffic; @Autowired JdbcTemplate jdbc; @Autowired com.amhs.fleet.FleetState fleet; @Autowired com.amhs.fleet.VehicleConfiguration configuration;
 @Autowired com.amhs.equipment.SimulatedEquipment equipment;
 @Autowired TransportAssignment assignment;
 @Autowired com.amhs.transport.TransportLedger ledger;
 private static final long SITE=1, OHT=1;

 @BeforeEach @AfterEach void clean(){
  simulator.clear();
  jdbc.update("DELETE FROM equipment.simulation_state");
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  // Queued work left by other cases would be dispatched to the simulated vehicle too.
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status='QUEUED'");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id<>?",SITE,OHT);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?),home_node_id=NULL WHERE site_id=?",OHT,SITE);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE WHERE site_id=?",SITE);
 }
 @BeforeEach void options(){
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE,node_count=4,more_enabled=TRUE,more_type='NODE',"
   +"more_remaining_nodes=1,more_min_remaining_nodes=0,more_vehicle_signal=FALSE,transfer_max_attempts=3,run_mode='SIMULATION'"
   +" WHERE site_id=?",SITE);
 }
 @AfterEach void restore(){
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=FALSE,node_count=20,more_remaining_nodes=3,"
   +"more_min_remaining_nodes=1,run_mode='OPERATION' WHERE site_id=?",SITE);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }

 private long accept(String to){
  return orders.accept(SITE,new OrderRequest("SIM",UUID.randomUUID().toString(),"OHT-A",to,UUID.randomUUID().toString(),50),"test",null).order().id();
 }
 /** What the running loop does each tick: a sweep of the site, then one step of each vehicle. */
 private TransportOrder runUntilFinished(long id,int ticks){
  for(int i=0;i<ticks;i++){
   sweep.run(SITE,"test",null);
   simulator.step(SITE);
   var order=orders.find(SITE,id).orElseThrow();
   if(order.status().isTerminal()||order.status()==OrderStatus.RECOVERY_REQUIRED) return order;
  }
  return orders.find(SITE,id).orElseThrow();
 }
 private List<String> path(long id){
  return orders.events(SITE,id).stream().map(e->e.newStatus().name()).toList();
 }

 private long unit(String port){
  return jdbc.queryForObject("SELECT equipment_unit_id FROM topology.port WHERE site_id=? AND code=? AND revision_id=1",Long.class,SITE,port);
 }
 @Test void virtualEquipmentAcceptsStateChangesAndCompletesWithoutRealReadiness(){
  equipment.change(SITE,unit("OHT-A"),new com.amhs.equipment.SimulatedEquipment.Change(null,null,"OFFLINE"));
  equipment.change(SITE,unit("OHT-C"),new com.amhs.equipment.SimulatedEquipment.Change(null,null,"ERROR"));
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  assertThat(runUntilFinished(id,80).status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(equipment.list(SITE).stream().filter(s->s.unitId()==unit("OHT-C")).findFirst().orElseThrow().status()).isEqualTo("IDLE");
 }
 @Test void prohibitionBlocksIntakeAndAssignmentButCanBeCleared(){
  long unit=unit("OHT-A");
  equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(null,false,null));
  assertThatThrownBy(()->accept("OHT-C")).isInstanceOf(TransportRejected.class)
   .extracting(e->((TransportRejected)e).code()).isEqualTo("EQUIPMENT_CALL_DISABLED");
  assertThatThrownBy(()->equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(null,null,"READY")))
   .hasMessage("EQUIPMENT_PROHIBITED");
  equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(null,true,null));
  long id=accept("OHT-C");
  simulator.place(SITE,OHT,101L);
  equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(false,null,null));
  assertThatThrownBy(()->assignment.assign(SITE,id,"test",null)).isInstanceOf(TransportRejected.class)
   .extracting(e->((TransportRejected)e).code()).isEqualTo("EQUIPMENT_DISABLED");
  equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(true,null,null));
  assertThat(runUntilFinished(id,80).status()).isEqualTo(OrderStatus.COMPLETED);
 }
 @Test void prohibitionDuringPickupHoldsTheTransferUntilReleased(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  for(int i=0;i<50&&orders.find(SITE,id).orElseThrow().status()!=OrderStatus.LOADING;i++){
   sweep.run(SITE,"test",null); simulator.step(SITE);
  }
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.LOADING);
  equipment.change(SITE,unit("OHT-A"),new com.amhs.equipment.SimulatedEquipment.Change(null,false,null));
  assertThat(runUntilFinished(id,3).status()).isEqualTo(OrderStatus.LOADING);
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().carrierPresent()).isFalse();
  equipment.change(SITE,unit("OHT-A"),new com.amhs.equipment.SimulatedEquipment.Change(null,true,null));
  assertThat(runUntilFinished(id,80).status()).isEqualTo(OrderStatus.COMPLETED);
 }
 @Test void operationRejectsVirtualEquipmentAndStopsVirtualSteps(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  sweep.run(SITE,"test",null);
  var before=fleet.find(SITE,1,OHT).orElseThrow();
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=?",SITE);
  assertThatThrownBy(()->equipment.change(SITE,unit("OHT-A"),new com.amhs.equipment.SimulatedEquipment.Change(null,null,"READY")))
   .hasMessage("SITE_NOT_IN_SIMULATION");
  assertThat(simulator.step(SITE)).isEmpty();
  simulator.drive(SITE,30);
  assertThat(fleet.find(SITE,1,OHT).orElseThrow()).isEqualTo(before);
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
 }
 @Test void configuredUseProhibitionCannotBeOverriddenBySimulation(){
  long unit=unit("OHT-C");
  jdbc.update("UPDATE equipment.unit SET enabled=FALSE WHERE id=?",unit);
  try {
   equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(true,true,null));
   assertThatThrownBy(()->accept("OHT-C")).isInstanceOf(TransportRejected.class)
    .extracting(e->((TransportRejected)e).code()).isEqualTo("EQUIPMENT_DISABLED");
   assertThatThrownBy(()->equipment.change(SITE,unit,new com.amhs.equipment.SimulatedEquipment.Change(null,null,"READY")))
    .hasMessage("EQUIPMENT_PROHIBITED");
  } finally {jdbc.update("UPDATE equipment.unit SET enabled=TRUE WHERE id=?",unit);}
 }
 @Test void destinationCallProhibitionKeepsTheCarrierOnTheVehicle(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  for(int i=0;i<50&&orders.find(SITE,id).orElseThrow().status()!=OrderStatus.UNLOADING;i++){
   sweep.run(SITE,"test",null); simulator.step(SITE);
  }
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.UNLOADING);
  equipment.change(SITE,unit("OHT-C"),new com.amhs.equipment.SimulatedEquipment.Change(null,false,null));
  assertThat(runUntilFinished(id,3).status()).isEqualTo(OrderStatus.UNLOADING);
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().carrierPresent()).isTrue();
  equipment.change(SITE,unit("OHT-C"),new com.amhs.equipment.SimulatedEquipment.Change(null,true,null));
  assertThat(runUntilFinished(id,80).status()).isEqualTo(OrderStatus.COMPLETED);
 }

 @Test void oneVehicleCarriesOneOrderFromAcceptanceToCompletion(){
  simulator.place(SITE,OHT,118L);
  long id=accept("OHT-C");
  var done=runUntilFinished(id,80);
  assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(done.carrierLocation()).isEqualTo(CarrierLocation.DESTINATION);
  assertThat(path(id)).containsSubsequence("QUEUED","ASSIGNED","TO_SOURCE","LOADING","TO_DESTINATION","UNLOADING","COMPLETED");
  // The pickup and the drop rest on the load sensor, recorded with the event.
  assertThat(jdbc.queryForList("SELECT evidence_kind||':'||carrier_location FROM transport.order_event"
   +" WHERE order_id=? AND carrier_location IS NOT NULL ORDER BY seq",String.class,id))
   .containsExactly("SENSOR_TRANSITION:VEHICLE","SENSOR_TRANSITION:DESTINATION");
  // Standing at the destination, holding only where it stands.
  assertThat(traffic.heldBy(SITE,OHT).stream().map(NodeHold::nodeId).toList()).containsOnly(105L);
 }

 @Test void aVehicleSwitchedToManualOrPausedStaysWhereItIsUntilGivenBack(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  // Run until it is on its way, then take it out of the controller's hands.
  for(int i=0;i<20&&fleet.find(SITE,1,OHT).orElseThrow().nodeId()==101L;i++){ sweep.run(SITE,"test",null); simulator.step(SITE); }
  for(var held:List.of(new String[]{"MANUAL","IDLE"},new String[]{"REMOTE","PAUSED"},new String[]{"REMOTE","ERROR"})){
   long at=fleet.find(SITE,1,OHT).orElseThrow().nodeId();
   fleet.report(SITE,1,OHT,new com.amhs.fleet.VehicleReport(at,true,false,false,false,"SIMULATED",null,held[0],held[1]));
   for(int i=0;i<3;i++){
    sweep.run(SITE,"test",null);
    var step=simulator.step(SITE).stream().filter(s->s.vehicleId()==OHT).findFirst().orElseThrow();
    assertThat(step.action()).startsWith("HELD_");
    var now=fleet.find(SITE,1,OHT).orElseThrow();
    assertThat(now.nodeId()).isEqualTo(at);
    assertThat(now.mode()).isEqualTo(held[0]);
    assertThat(now.status()).isEqualTo(held[1]);
   }
  }
  // Given back to the controller, it carries on and finishes.
  long at=fleet.find(SITE,1,OHT).orElseThrow().nodeId();
  fleet.report(SITE,1,OHT,new com.amhs.fleet.VehicleReport(at,true,false,false,false,"SIMULATED",null,"REMOTE","IDLE"));
  assertThat(runUntilFinished(id,80).status()).isEqualTo(OrderStatus.COMPLETED);
 }

 @Test void aPickupTheSensorSaysDidNotHappenIsDoneAgain(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  simulator.failNextTransfer(OHT);
  var done=runUntilFinished(id,80);
  assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(path(id)).containsSubsequence("LOADING","TO_SOURCE","LOADING","TO_DESTINATION");
  assertThat(jdbc.queryForObject("SELECT transfer_attempt FROM transport.order WHERE id=?",Integer.class,id)).isEqualTo(1);
 }

 @Test void aCommandForAVehicleNobodyPlacedGoesNowhere(){
  // Vehicle 1 is not placed in this case, so nothing is simulated and its command is not delivered.
  fleet.report(SITE,1,OHT,new com.amhs.fleet.VehicleReport(101L,true,true,true,false,"SIMULATED"));
  assertThat(simulator.isSimulated(OHT)).isFalse();
  long id=accept("OHT-C");
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.assigned()).contains(id);
  assertThat(pass.published().notDelivered()).isPositive();
 }

 @Test void aSiteOnRealDevicesSendsNothingToSimulatedVehicles(){
  simulator.place(SITE,OHT,101L);
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=?",SITE);
  long id=accept("OHT-C");
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.assigned()).contains(id);
  assertThat(pass.published().notDelivered()).isPositive();
  assertThat(jdbc.queryForObject("SELECT last_error FROM integration.command_outbox WHERE order_id=? ORDER BY id LIMIT 1",
   String.class,id)).isEqualTo("ADAPTER_NOT_IMPLEMENTED");
 }

 /**
  * Putting a vehicle on a node is not driving it there, so nothing it held is still held.
  *
  * <p>Leaving those would reserve places for a vehicle that is not at them and is not coming back,
  * and the next vehicle routed through would wait for one that had already gone.
  */
 @Test void placingAVehicleGivesBackEverythingItWasHolding(){
  simulator.place(SITE,OHT,101L);
  assertThat(traffic.request(SITE,1,OHT,null,java.util.List.of(101L,102L,103L)).complete()).isTrue();
  assertThat(traffic.heldBy(SITE,OHT)).hasSize(3);

  simulator.place(SITE,OHT,110L);
  assertThat(traffic.heldBy(SITE,OHT)).as("it is not at any of them any more").isEmpty();
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().nodeId()).isEqualTo(110L);
 }

 /**
  * It is refused while an order is running, because the order stores the route the vehicle is
  * partway along and moving it off that route leaves a plan for a journey nobody is making.
  */
 @Test void placingIsRefusedWhileTheVehicleHasWork(){
  simulator.place(SITE,OHT,101L);
  long id=accept("OHT-C");
  assertThat(sweep.run(SITE,"test",null).assigned()).contains(id);
  assertThatThrownBy(()->simulator.place(SITE,OHT,110L))
   .isInstanceOf(IllegalArgumentException.class).hasMessage("VEHICLE_HAS_ORDER");

  // Saying so does it, and the order is put where moving the vehicle leaves it. Nothing had been
  // picked up here, so the work simply ends rather than becoming a carrier nobody can find.
  simulator.place(SITE,OHT,110L,true);
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().nodeId()).isEqualTo(110L);
  // Nothing had been picked up, so the work simply ends -- and it ends rather than waiting for a
  // report from a vehicle that has been taken off it and will never send one.
  assertThat(ledger.find(SITE,id).orElseThrow().status())
   .isEqualTo(com.amhs.transport.OrderStatus.CANCELLED);
  assertThat(traffic.heldBy(SITE,OHT)).isEmpty();
 }

 /** Setting the charge by hand says it is this now, so an injected pickup fault stops. */
 @Test void settingTheChargeByHandStopsItBeingTakenStraightBackDown(){
  simulator.place(SITE,OHT,101L);
  simulator.setPowerPickup(OHT,false);
  assertThat(simulator.pickupFaults()).contains(OHT);

  simulator.setBattery(SITE,OHT,100.0);
  assertThat(simulator.pickupFaults()).as("the control is not overruled by a fault left running")
   .doesNotContain(OHT);
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().batteryPct()).isEqualTo(100.0);
 }

 @Test void enteringSimulationPutsTheVehiclesInUseOnTheTrack(){
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE WHERE site_id=?",SITE);
  simulator.placeAll(SITE,configuration);
  assertThat(simulator.isSimulated(OHT)).isTrue();
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().source()).isEqualTo("SIMULATED");
  simulator.clearExcept(java.util.Set.of());
  assertThat(simulator.isSimulated(OHT)).isFalse();
 }
}
