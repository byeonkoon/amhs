package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.topology.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A stopped move ends only on the vehicle's own answer; a simulated vehicle's state is changed
 * without touching where it is; simulated vehicles drive segments, curved ones too, at their speed.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MotionAndStopSqlTest {
 @Autowired ZoneEvacuation moves; @Autowired TransportLedger ledger; @Autowired CommandOutbox outbox;
 @Autowired FleetState fleet; @Autowired TrafficControl traffic; @Autowired SimulatedVehicles simulator;
 @Autowired SiteSweep sweep; @Autowired TopologyQuery topology; @Autowired JdbcTemplate jdbc;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx; @Autowired MockMvc mvc;
 private static final long SITE=1, REVISION=1, OHT=1;
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";

 @BeforeEach @AfterEach void clean(){
  simulator.clear();
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("UPDATE transport.order SET status='CANCELLED',terminal_at=now() WHERE status='QUEUED'");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  // Sent and never answered, left by earlier cases: they would hold this vehicle's next command back.
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED' WHERE status='SENT' AND site_id=? AND vehicle_id=?",SITE,OHT);
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id<>?",SITE,OHT);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=FALSE,home_node_id=NULL,speed_mm_s=2000 WHERE site_id=?",SITE);
  jdbc.update("UPDATE topology.edge SET curve_mm=0 WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20,auto_dispatch=FALSE,run_mode='OPERATION' WHERE site_id=?",SITE);
 }
 private VehicleSnapshot report(Long node,String status){
  return tx.execute(s->fleet.report(SITE,REVISION,OHT,new VehicleReport(node,true,true,false,false,"SIMULATED",null,"REMOTE",status)));
 }
 private ZoneEvacuation.Step stepOf(){
  return tx.execute(s->moves.evacuateAll(SITE)).stream().filter(x->x.vehicleId()==OHT).findFirst().orElseThrow();
 }
 private List<Long> held(){ return traffic.heldBy(SITE,OHT).stream().map(NodeHold::nodeId).toList(); }

 // ---- G1: a stopped move ends on the vehicle's answer, not on its receipt ----

 @Test void anAcknowledgedMoveIsNotEndedByTheIdleReportFromBeforeIt(){
  report(118L,"IDLE");
  tx.execute(s->moves.moveTo(SITE,OHT,105L));
  var sent=tx.execute(s->outbox.claim(SITE,500,20)).stream().filter(c->Long.valueOf(OHT).equals(c.vehicleId())).findFirst().orElseThrow();
  tx.execute(s->outbox.settle(sent.commandId(),DeviceChannel.Verdict.delivered(),java.time.Duration.ofSeconds(1)));
  tx.execute(s->outbox.acknowledge(sent.commandId()));
  var road=held();
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  // Accepted, and the only idle report is the one from before the move: the vehicle may be driving.
  assertThat(stepOf().decision()).isEqualTo("VEHICLE_DISABLED_STOPPING");
  assertThat(ledger.relocatingVehicleIds(SITE)).contains(OHT);
  assertThat(held()).isEqualTo(road);
  // An old report from a vehicle gone offline is no answer either.
  jdbc.update("UPDATE fleet.vehicle_state SET online=FALSE,reported_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  assertThat(stepOf().decision()).isEqualTo("VEHICLE_DISABLED_STOPPING");
  assertThat(held()).isEqualTo(road);
  // It answers: online, idle, after the stop was decided. Now the move ends and the road ahead is freed.
  report(118L,"IDLE");
  assertThat(stepOf().decision()).isEqualTo("VEHICLE_DISABLED");
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(held()).containsOnly(118L);
 }

 @Test void aMoveThatNeverReachedTheVehicleEndsAtOnce(){
  report(118L,"IDLE");
  tx.execute(s->moves.moveTo(SITE,OHT,105L));
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(stepOf().decision()).isEqualTo("VEHICLE_DISABLED");
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(held()).containsOnly(118L);
 }

 // ---- simulated motion, G2 ----

 /** Simulation on, vehicle placed at 118 and sent to 105, the MOVE delivered. */
 private void drivingFrom118(){
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION' WHERE site_id=?",SITE);
  simulator.place(SITE,OHT,118L);
  tx.execute(s->moves.moveTo(SITE,OHT,105L));
  sweep.run(SITE,"test",null);
  assertThat(held().size()).isGreaterThan(1);
 }
 private VehicleSnapshot now(){ return fleet.find(SITE,REVISION,OHT).orElseThrow(); }
 private double straightDistance(long a,long b,double x,double y){
  var map=topology.getMap(SITE,REVISION);
  var na=map.nodes().stream().filter(n->n.id()==a).findFirst().orElseThrow();
  var nb=map.nodes().stream().filter(n->n.id()==b).findFirst().orElseThrow();
  double dx=nb.x()-na.x(), dy=nb.y()-na.y();
  return Math.abs(dy*(x-na.x())-dx*(y-na.y()))/Math.hypot(dx,dy);
 }

 @Test void aSimulatedVehicleDrivesAlongTheSegmentAtItsSpeedAndChangesNodeOnArrival(){
  drivingFrom118();
  long next=held().get(1);
  var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
  double seconds=shape.length()/2000.0;
  simulator.drive(SITE,seconds*0.4);
  var mid=now();
  // Between the two nodes: still counted on 118, its coordinates on the way, moving.
  assertThat(mid.nodeId()).isEqualTo(118L);
  assertThat(mid.xMm()).isNotNull();
  assertThat(mid.status()).isEqualTo("MOVING");
  assertThat(held()).contains(118L,next);
  simulator.drive(SITE,seconds*0.7);
  var arrived=now();
  assertThat(arrived.nodeId()).as("speed %s length %s mid %s,%s now %s,%s held %s",arrived.speedMmS(),shape.length(),
   mid.xMm(),mid.yMm(),arrived.xMm(),arrived.yMm(),held()).isEqualTo(next);
  assertThat(held()).doesNotContain(118L);
 }

 @Test void aCurvedSegmentIsDrivenOnItsCurve(){
  drivingFrom118();
  long next=held().get(1);
  jdbc.update("UPDATE topology.edge SET curve_mm=800 WHERE site_id=? AND ((from_node_id=118 AND to_node_id=?) OR (from_node_id=? AND to_node_id=118))",
   SITE,next,next);
  var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
  assertThat(shape.length()).isGreaterThan(Math.hypot(shape.bx()-shape.ax(),shape.by()-shape.ay()));
  simulator.drive(SITE,shape.length()/2/2000.0);
  var mid=now();
  // Halfway along a curve that bulges 800 mm, the vehicle is well off the straight line.
  assertThat(straightDistance(118L,next,mid.xMm(),mid.yMm())).isGreaterThan(600);
 }

 @Test void changingTheStateKeepsWhereTheVehicleIsAndHoldsItThere() throws Exception {
  drivingFrom118();
  long next=held().get(1);
  var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
  simulator.drive(SITE,shape.length()*0.5/2000.0);
  var before=now();
  // Only mode and status are sent; the position is never taken from the request.
  mvc.perform(put("/api/v1/sites/1/simulation/vehicles/1/state").header("Authorization",TOKEN)
    .contentType("application/json").content("{\"mode\":\"REMOTE\",\"status\":\"PAUSED\",\"nodeId\":105}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"))
   .andExpect(jsonPath("$.nodeId").value(118));
  var paused=now();
  assertThat(paused.nodeId()).isEqualTo(118L);
  assertThat(paused.xMm()).isEqualTo(before.xMm());
  simulator.drive(SITE,5);
  simulator.settle(SITE);
  assertThat(now().xMm()).isEqualTo(before.xMm());
  assertThat(now().status()).isEqualTo("PAUSED");
  assertThat(held()).contains(118L,next);
  // Given back, it carries on from where it stopped rather than from the node.
  simulator.setState(SITE,OHT,"REMOTE","MOVING");
  simulator.drive(SITE,shape.length()*0.1/2000.0);
  var resumed=now();
  assertThat(resumed.nodeId()).isEqualTo(118L);
  assertThat(Math.hypot(resumed.xMm()-shape.ax(),resumed.yMm()-shape.ay()))
   .isGreaterThan(Math.hypot(before.xMm()-shape.ax(),before.yMm()-shape.ay()));
 }

 @Test void aVehicleNotSimulatedCannotHaveItsStateSet() throws Exception {
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION' WHERE site_id=?",SITE);
  mvc.perform(put("/api/v1/sites/1/simulation/vehicles/1/state").header("Authorization",TOKEN)
    .contentType("application/json").content("{\"mode\":\"REMOTE\",\"status\":\"PAUSED\"}"))
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_A_SIMULATED_VEHICLE"));
 }

 // ---- layout facts the map shows ----

 @Test void portsCarryTheirEquipmentAndSegmentsTheirCurve(){
  jdbc.update("UPDATE topology.edge SET curve_mm=-450 WHERE site_id=? AND id=(SELECT min(id) FROM topology.edge WHERE site_id=?)",SITE,SITE);
  var map=topology.getMap(SITE,REVISION);
  assertThat(map.ports()).anySatisfy(p->{
   assertThat(p.code()).isEqualTo("OHT-A");
   assertThat(p.equipmentCode()).isEqualTo("STK-01");
   assertThat(p.equipmentName()).isNotBlank();
  });
  assertThat(map.edges()).anyMatch(e->e.curveMm()==-450);
 }

 @Test void speedAndCurveAreValidated(){
  assertThatThrownBy(()->new EdgeSettings(1,2,1,true,200_000.0).validate()).hasMessageContaining("INVALID_CURVE");
  var oht=new VehicleSettings(1,"X","x",com.amhs.platform.VehicleType.OHT,1,1,1,101,null,null,true,true,
   "CONTROLLER_MANAGED","DISABLED","DISABLED",null);
  assertThat(oht.speedMmS()).isEqualTo(VehicleSettings.defaultSpeed(com.amhs.platform.VehicleType.OHT));
  assertThatThrownBy(()->new VehicleSettings(1,"X","x",com.amhs.platform.VehicleType.AGV,1,1,1,201,null,null,true,true,
   "CONTROLLER_MANAGED","DISABLED","DISABLED",null,10).validate()).hasMessageContaining("INVALID_SPEED");
 }
}
