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
 private static final long SITE=1, OHT=1;

 @BeforeEach @AfterEach void clean(){
  simulator.clear();
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
  return orders.accept(SITE,new OrderRequest("SIM",UUID.randomUUID().toString(),"OHT-A",to,"SIM-C",50),"test",null).order().id();
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

 @Test void enteringSimulationPutsTheVehiclesInUseOnTheTrack(){
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE WHERE site_id=?",SITE);
  simulator.placeAll(SITE,configuration);
  assertThat(simulator.isSimulated(OHT)).isTrue();
  assertThat(fleet.find(SITE,1,OHT).orElseThrow().source()).isEqualTo("SIMULATED");
  simulator.clearExcept(java.util.Set.of());
  assertThat(simulator.isSimulated(OHT)).isFalse();
 }
}
