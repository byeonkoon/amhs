package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.transport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * What an operator sees and does with one vehicle: its mode, status and load on their own axes, and
 * sending it to a node by hand under the conditions the existing system checks.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class VehicleControlSqlTest {
 @Autowired FleetState fleet; @Autowired ZoneEvacuation moves; @Autowired TransportLedger ledger;
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment; @Autowired CommandOutbox outbox;
 @Autowired com.amhs.traffic.TrafficControl traffic; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
 @Autowired MockMvc mvc;
 private static final long SITE=1, REVISION=1, OHT=1;
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";

 @BeforeEach void prepare(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=(id=?) WHERE site_id=?",OHT,SITE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20 WHERE site_id=?",SITE);
  report(118L,"REMOTE","IDLE");
 }
 private VehicleSnapshot report(Long node,String mode,String status){
  return fleet.report(SITE,REVISION,OHT,new VehicleReport(node,true,false,false,false,"SIMULATED",null,mode,status));
 }
 private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){
  assertThatThrownBy(call).isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo(code));
 }

 // ---- mode, status and load ----

 @Test void modeAndStatusAreKeptOnTheirOwnAxesAndDecideRemoteAndIdle(){
  var semi=report(118L,"SEMI_AUTO","IDLE");
  assertThat(semi.mode()).isEqualTo("SEMI_AUTO");
  assertThat(semi.remote()).isFalse();
  var fault=report(118L,"REMOTE","ERROR");
  assertThat(fault.status()).isEqualTo("ERROR");
  assertThat(fault.remote()).isTrue();
  assertThat(fault.idle()).isFalse();
  // A report that only sets the old flags still reads the same way.
  var plain=fleet.report(SITE,REVISION,OHT,new VehicleReport(118L,true,true,true,true,"SIMULATED"));
  assertThat(plain.mode()).isEqualTo("REMOTE");
  assertThat(plain.status()).isEqualTo("IDLE");
  assertThat(plain.carrierPresent()).isTrue();
  assertThatThrownBy(()->report(118L,"AUTO","IDLE")).hasMessage("INVALID_MODE");
  assertThatThrownBy(()->report(118L,"REMOTE","FLYING")).hasMessage("INVALID_STATUS");
 }

 @Test void theStateEndpointTakesModeAndStatus() throws Exception {
  mvc.perform(put("/api/v1/sites/1/maps/1/vehicle-state/1").header("Authorization",TOKEN).contentType("application/json")
    .content("{\"nodeId\":118,\"online\":true,\"mode\":\"REMOTE\",\"status\":\"CHARGING\",\"carrierPresent\":false}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.vehicle.status").value("CHARGING"))
   .andExpect(jsonPath("$.vehicle.remote").value(true)).andExpect(jsonPath("$.vehicle.idle").value(false));
  mvc.perform(put("/api/v1/sites/1/maps/1/vehicle-state/1").header("Authorization",TOKEN).contentType("application/json")
    .content("{\"nodeId\":118,\"online\":true,\"mode\":\"AUTO\"}"))
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_MODE"));
 }

 // ---- an operator's move ----

 @Test void takingAVehicleOutOfUseTakesBackAMoveThatNeverLeft(){
  moves.moveTo(SITE,OHT,105L);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(moves.evacuateAll(SITE)).anyMatch(s->s.vehicleId()==OHT&&s.decision().equals("VEHICLE_DISABLED"));
  // The MOVE is withdrawn, so nothing hands it to the vehicle any more.
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(outbox.claim(SITE,500,20)).noneMatch(c->Long.valueOf(OHT).equals(c.vehicleId()));
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(traffic.heldBy(SITE,OHT).stream().map(com.amhs.traffic.NodeHold::nodeId).toList()).containsOnly(118L);
 }

 @Test void aMoveThatAlreadyLeftKeepsItsRoadUntilTheVehicleHasStopped(){
  moves.moveTo(SITE,OHT,105L);
  var sent=outbox.claim(SITE,500,20).stream().filter(c->Long.valueOf(OHT).equals(c.vehicleId())).findFirst().orElseThrow();
  outbox.settle(sent.commandId(),DeviceChannel.Verdict.delivered(),java.time.Duration.ofSeconds(1));
  var road=traffic.heldBy(SITE,OHT).stream().map(com.amhs.traffic.NodeHold::nodeId).toList();
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  // Sent without an answer: the vehicle may already be on its way, so its road stays.
  assertThat(moves.evacuateAll(SITE)).anyMatch(s->s.vehicleId()==OHT&&s.decision().equals("VEHICLE_DISABLED_STOPPING"));
  assertThat(ledger.relocatingVehicleIds(SITE)).contains(OHT);
  assertThat(traffic.heldBy(SITE,OHT).stream().map(com.amhs.traffic.NodeHold::nodeId).toList()).isEqualTo(road);
  // Accepted and driving: still kept.
  outbox.acknowledge(sent.commandId());
  report(road.get(1),"REMOTE","MOVING");
  traffic.advanced(SITE,OHT,road.get(1));
  assertThat(moves.evacuateAll(SITE)).anyMatch(s->s.vehicleId()==OHT&&s.decision().equals("VEHICLE_DISABLED_STOPPING"));
  // Stopped where it is: the move closes and what lay ahead is freed.
  report(road.get(1),"REMOTE","IDLE");
  assertThat(moves.evacuateAll(SITE)).anyMatch(s->s.vehicleId()==OHT&&s.decision().equals("VEHICLE_DISABLED"));
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(traffic.heldBy(SITE,OHT).stream().map(com.amhs.traffic.NodeHold::nodeId).toList()).containsOnly(road.get(1));
 }

 @Test void anIdleRemoteVehicleIsSentToTheChosenNodeAndArrives() throws Exception {
  var step=moves.moveTo(SITE,OHT,105L);
  assertThat(step.decision()).isEqualTo("MOVING_TO_TARGET");
  var move=outbox.unresolvedForVehicle(SITE,OHT).getFirst();
  assertThat(move.orderId()).isNull();
  assertThat(json.readTree(move.payload()).get("purpose").asText()).isEqualTo("MANUAL_MOVE");
  assertThat(json.readTree(move.payload()).get("targetNodeId").asLong()).isEqualTo(105L);
  // A second request while it is on its way is refused, and so is new work for it.
  rejected(()->moves.moveTo(SITE,OHT,110L),"VEHICLE_ALREADY_MOVING");
  // It drives there; the move closes on arrival like any move without an order.
  outbox.claim(SITE,500,20);
  for(var c:outbox.unresolvedForVehicle(SITE,OHT)) outbox.acknowledge(c.commandId());
  report(105L,"REMOTE","IDLE");
  traffic.advanced(SITE,OHT,105L);
  assertThat(moves.evacuateAll(SITE)).anyMatch(s->s.vehicleId()==OHT&&s.decision().equals("ARRIVED"));
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
 }

 @Test void aMoveIsRefusedUnlessTheVehicleIsInUseRemoteIdleAndFree(){
  rejected(()->moves.moveTo(SITE,OHT,999_999L),"TARGET_NODE_INVALID");
  rejected(()->moves.moveTo(SITE,OHT,118L),"ALREADY_AT_TARGET");
  report(118L,"MANUAL","IDLE");
  rejected(()->moves.moveTo(SITE,OHT,105L),"VEHICLE_NOT_REMOTE");
  report(118L,"REMOTE","ERROR");
  rejected(()->moves.moveTo(SITE,OHT,105L),"VEHICLE_NOT_IDLE");
  report(118L,"REMOTE","IDLE");
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  rejected(()->moves.moveTo(SITE,OHT,105L),"VEHICLE_DISABLED");
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT);
  long id=orders.accept(SITE,new OrderRequest("CTRL",UUID.randomUUID().toString(),"OHT-A","OHT-C","C",50),"test",null).order().id();
  assignment.assign(SITE,id,"test",null);
  rejected(()->moves.moveTo(SITE,OHT,105L),"VEHICLE_HAS_ORDER");
 }

 @Test void anOperatorMayMoveAVehicleKeptOutOfAutomaticWork(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(moves.moveTo(SITE,OHT,105L).decision()).isEqualTo("MOVING_TO_TARGET");
 }

 @Test void theMoveEndpointNeedsAnOperatorAndExplainsRefusals() throws Exception {
  mvc.perform(post("/api/v1/sites/1/vehicles/1/move").contentType("application/json").content("{\"targetNodeId\":105}"))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/sites/1/vehicles/1/move").header("Authorization",TOKEN).contentType("application/json")
    .content("{\"targetNodeId\":118}"))
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ALREADY_AT_TARGET"));
  mvc.perform(post("/api/v1/sites/1/vehicles/1/move").header("Authorization",TOKEN).contentType("application/json")
    .content("{\"targetNodeId\":105}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("MOVING_TO_TARGET"));
 }

 // ---- the work list and the history ----

 @Test void theWorkListHoldsOnlyOrdersStillGoingAndTheHistoryTheFinishedOnes() throws Exception {
  long going=orders.accept(SITE,new OrderRequest("CTRL",UUID.randomUUID().toString(),"OHT-A","OHT-C","C",50),"test",null).order().id();
  long done=orders.accept(SITE,new OrderRequest("CTRL",UUID.randomUUID().toString(),"OHT-A","OHT-C","C",50),"test",null).order().id();
  orders.cancel(SITE,done,"test","test",null);
  assertThat(orders.listActive(SITE,500)).extracting(TransportOrder::id).contains(going).doesNotContain(done);
  assertThat(orders.listFinished(SITE,500)).extracting(TransportOrder::id).contains(done).doesNotContain(going);
  mvc.perform(get("/api/v1/sites/1/transport-orders?scope=active&limit=500"))
   .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id==%d)]".formatted(done)).isEmpty());
 }
}
