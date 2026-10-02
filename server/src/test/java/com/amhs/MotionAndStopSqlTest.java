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
  jdbc.update("UPDATE platform.site_operation SET node_count=20,more_remaining_nodes=3,more_min_remaining_nodes=1,"
   +"auto_dispatch=FALSE,run_mode='OPERATION' WHERE site_id=?",SITE);
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

 // ---- H1, H2: part way along a segment ----

 private void setStateLikeTheScreen(String mode,String status) throws Exception {
  mvc.perform(put("/api/v1/sites/1/simulation/vehicles/1/state").header("Authorization",TOKEN)
    .contentType("application/json").content("{\"mode\":\""+mode+"\",\"status\":\""+status+"\"}"))
   .andExpect(status().isOk());
 }

 @Test void anIdleClaimPartWayAlongASegmentIsNoStop() throws Exception {
  for(var how:List.of("disable","stop")){
   clean();
   drivingFrom118();
   long next=held().get(1);
   var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
   simulator.drive(SITE,shape.length()*0.4/2000.0);
   var road=held();
   if(how.equals("disable")){
    jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
    assertThat(stepOf().decision()).as(how).isEqualTo("VEHICLE_DISABLED_STOPPING");
   }else{
    assertThat(tx.execute(t->moves.stopMove(SITE,OHT)).decision()).as(how).isEqualTo("MOVE_STOPPED_STOPPING");
   }
   // The screen's "remote, idle" while it is between two nodes: still not standing anywhere.
   setStateLikeTheScreen("REMOTE","IDLE");
   assertThat(now().betweenNodes()).as(how).isTrue();
   assertThat(now().idle()).as(how).isFalse();
   // Even a stored "idle" there is not idle: the move and its road stay.
   jdbc.update("UPDATE fleet.vehicle_state SET status='IDLE',idle=TRUE,reported_at=clock_timestamp() WHERE site_id=? AND vehicle_id=?",SITE,OHT);
   assertThat(now().idle()).as(how).isFalse();
   tx.execute(t->moves.evacuateAll(SITE));
   assertThat(ledger.relocatingVehicleIds(SITE)).as(how).contains(OHT);
   assertThat(held()).as(how).isEqualTo(road);
   assertThat(outbox.unresolvedForVehicle(SITE,OHT)).as(how).anyMatch(c->c.commandType().equals("STOP"));
   // The STOP reaches it, it finishes the segment and stands on the next node: now the move closes.
   jdbc.update("UPDATE fleet.vehicle_state SET status='MOVING',idle=FALSE WHERE site_id=? AND vehicle_id=?",SITE,OHT);
   simulator.settle(SITE);
   sweep.run(SITE,"test",null);
   for(int i=0;i<10;i++){ simulator.settle(SITE); simulator.drive(SITE,1.0); sweep.run(SITE,"test",null); }
   assertThat(now().nodeId()).as(how).isEqualTo(next);
   assertThat(now().betweenNodes()).as(how).isFalse();
   tx.execute(t->moves.evacuateAll(SITE));
   assertThat(ledger.relocatingVehicleIds(SITE)).as(how).doesNotContain(OHT);
   assertThat(held()).as(how).containsOnly(next);
  }
 }

 @Test void resumingPartWayAlongASegmentIsReportedAsMoving() throws Exception {
  drivingFrom118();
  long next=held().get(1);
  var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
  simulator.drive(SITE,shape.length()*0.4/2000.0);
  setStateLikeTheScreen("REMOTE","PAUSED");
  var paused=now();
  // Given back with the screen's "remote, idle": it carries on, and says it is moving.
  setStateLikeTheScreen("REMOTE","IDLE");
  assertThat(now().status()).isEqualTo("MOVING");
  simulator.drive(SITE,shape.length()*0.1/2000.0);
  var resumed=now();
  assertThat(resumed.status()).isEqualTo("MOVING");
  assertThat(resumed.idle()).isFalse();
  assertThat(Math.hypot(resumed.xMm()-shape.ax(),resumed.yMm()-shape.ay()))
   .isGreaterThan(Math.hypot(paused.xMm()-shape.ax(),paused.yMm()-shape.ay()));
 }

 // ---- an operator stops their move ----

 @Test void stoppingAMoveThatNeverLeftEndsItWhereTheVehicleStands(){
  report(118L,"IDLE");
  tx.execute(s->moves.moveTo(SITE,OHT,105L));
  var step=tx.execute(s->moves.stopMove(SITE,OHT));
  assertThat(step.decision()).isEqualTo("MOVE_STOPPED");
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(held()).containsOnly(118L);
 }

 @Test void aStoppedMoveHaltsAtTheEndOfTheSegmentItIsOnAndClosesThere(){
  drivingFrom118();
  var granted=held();
  long next=granted.get(1);
  assertThat(granted).contains(105L);
  var shape=SegmentShape.between(topology.getMap(SITE,REVISION),118L,next);
  simulator.drive(SITE,shape.length()*0.4/2000.0);
  // Stopped while it drives on what it was given: the move stays open, its road kept, a STOP queued.
  assertThat(tx.execute(s->moves.stopMove(SITE,OHT)).decision()).isEqualTo("MOVE_STOPPED_STOPPING");
  assertThat(ledger.openRelocations(SITE)).anySatisfy(r->{
   assertThat(r.vehicleId()).isEqualTo(OHT);
   assertThat(r.stopRequestedAt()).isNotNull();
  });
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).anyMatch(c->c.commandType().equals("STOP"));
  assertThat(held()).isEqualTo(granted);
  // The MOVE is acknowledged, then the STOP goes out and reaches it before it drives on.
  simulator.settle(SITE);
  sweep.run(SITE,"test",null);
  assertThat(now().nodeId()).isEqualTo(118L);
  // It finishes the segment it is on and stands there, idle.
  for(int i=0;i<10;i++){
   simulator.settle(SITE);
   simulator.drive(SITE,1.0);
   sweep.run(SITE,"test",null);
  }
  assertThat(now().nodeId()).isEqualTo(next);
  assertThat(now().status()).isEqualTo("IDLE");
  // Standing idle after the stop was asked: the move closes there, short of its target, and what
  // lay ahead is free again.
  tx.execute(t->moves.evacuateAll(SITE));
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(held()).containsOnly(next);
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
 }

 @Test void onlyAnOperatorsOpenMoveCanBeStopped(){
  report(118L,"IDLE");
  assertThatThrownBy(()->tx.execute(s->moves.stopMove(SITE,OHT)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NO_MOVE"));
  jdbc.update("INSERT INTO transport.relocation(site_id,revision_id,vehicle_id,zone_id,route_node_ids,target_node_id,purpose)"
   +" VALUES(?,?,?,NULL,ARRAY[118,119]::BIGINT[],119,'ZONE_EVACUATION')",SITE,REVISION,OHT);
  assertThatThrownBy(()->tx.execute(s->moves.stopMove(SITE,OHT)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NOT_A_MANUAL_MOVE"));
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
