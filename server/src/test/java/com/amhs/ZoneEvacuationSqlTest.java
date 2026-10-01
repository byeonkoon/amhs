package com.amhs;
import com.amhs.fleet.*;
import com.amhs.integration.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A vehicle that finished its work inside a full bay leaves it, so the vehicle going round the loop
 * waiting for a place can get in.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class ZoneEvacuationSqlTest {
 @Autowired ZoneEvacuation evacuation; @Autowired SiteSweep sweep; @Autowired TransportLedger ledger;
 @Autowired TransportOrders orders; @Autowired TransportAssignment assignment;
 @Autowired FleetState fleet; @Autowired TrafficControl traffic; @Autowired CommandOutbox outbox;
 @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;

 // The bay (zone OHT-BAY-1, capacity 1) branches off the ring at 108 through 121 -> 122 -> 123 and
 // rejoins at 110, a merge point. The port OHT-E sits at 122 inside it.
 private static final long SITE=1, REVISION=1, OHT=1, PHANTOM=9999, BAY_PORT=122, BRANCH=108;

 @BeforeEach void prepare(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id<>?",SITE,OHT);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?),home_node_id=NULL WHERE site_id=?",OHT,SITE);
  jdbc.update("UPDATE platform.site_operation SET node_count=20,merge_control='CONTROLLER' WHERE site_id=?",SITE);
  jdbc.update("UPDATE topology.node SET stop_node=FALSE WHERE site_id=? AND revision_id=?",SITE,REVISION);
 }

 private void standAt(long node,boolean idle){
  fleet.report(SITE,REVISION,OHT,new VehicleReport(node,true,true,idle,false,"SIMULATED"));
 }
 /** Unloaded at the bay port with no further work: it keeps the node it stands on and nothing else. */
 private void finishedInTheBay(){
  standAt(BAY_PORT,true);
  traffic.request(SITE,REVISION,OHT,null,List.of(BAY_PORT));
 }
 private ZoneEvacuation.Step stepOf(long vehicle){
  return evacuation.evacuateAll(SITE).stream().filter(s->s.vehicleId()==vehicle).findFirst().orElseThrow();
 }
 private void acknowledgeVehicle(){
  outbox.claim(SITE,500,20);
  for(var c:outbox.unresolvedForVehicle(SITE,OHT)) outbox.acknowledge(c.commandId());
 }
 private List<Long> inBay(){
  return traffic.zones(SITE).stream().filter(z->z.code().equals("OHT-BAY-1")).findFirst().orElseThrow().vehicleIds();
 }

 @Test void aVehicleThatFinishedInsideTheBayIsSentToTheNearestPlaceOutOfTheWay() throws Exception {
  finishedInTheBay();
  var step=stepOf(OHT);
  assertThat(step.decision()).isEqualTo("EVACUATING");
  // 110 is the merge point the bay rejoins at, so the vehicle goes one node past it.
  assertThat(step.addedNodeIds()).containsExactly(123L,110L,111L);
  var commands=outbox.unresolvedForVehicle(SITE,OHT);
  assertThat(commands).hasSize(1);
  var move=commands.getFirst();
  assertThat(move.orderId()).isNull();
  assertThat(move.commandType()).isEqualTo("MOVE");
  var payload=json.readTree(move.payload());
  assertThat(payload.get("purpose").asText()).isEqualTo("ZONE_EVACUATION");
  assertThat(payload.get("targetNodeId").asLong()).isEqualTo(111L);
  // While it moves out it is not offered to new work: that move would cross this one.
  assertThat(ledger.relocatingVehicleIds(SITE)).contains(OHT);
  long id=orders.accept(SITE,new OrderRequest("EVAC",UUID.randomUUID().toString(),"OHT-A","OHT-C","C",50),"test",null).order().id();
  assertThatThrownBy(()->assignment.assign(SITE,id,"test",null)).isInstanceOf(TransportRejected.class);
 }

 @Test void aConfiguredHomeOutsideTheZoneIsPreferred(){
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=116 WHERE site_id=? AND id=?",SITE,OHT);
  finishedInTheBay();
  var step=stepOf(OHT);
  assertThat(step.decision()).isEqualTo("EVACUATING");
  assertThat(ledger.openRelocations(SITE)).anySatisfy(r->{
   assertThat(r.vehicleId()).isEqualTo(OHT);
   assertThat(r.targetNodeId()).isEqualTo(116L);
  });
 }

 @Test void aHomeSomebodyElseStandsOnIsPassedOverForTheNearestClearPlace(){
  jdbc.update("UPDATE fleet.vehicle_profile SET home_node_id=116 WHERE site_id=? AND id=?",SITE,OHT);
  traffic.request(SITE,REVISION,PHANTOM,null,List.of(116L));
  finishedInTheBay();
  assertThat(stepOf(OHT).decision()).isEqualTo("EVACUATING");
  assertThat(ledger.openRelocations(SITE)).anySatisfy(r->assertThat(r.targetNodeId()).isEqualTo(111L));
 }

 @Test void theMoveIsContinuedOnlyOnceUsedAndClosesWhenTheVehicleArrives(){
  finishedInTheBay();
  assertThat(stepOf(OHT).decision()).isEqualTo("EVACUATING");
  assertThat(stepOf(OHT).decision()).isEqualTo("COMMAND_OUTSTANDING");
  acknowledgeVehicle();
  assertThat(stepOf(OHT).decision()).isEqualTo("AUTHORITY_NOT_USED");
  standAt(111,true);
  traffic.advanced(SITE,OHT,111L);
  assertThat(stepOf(OHT).decision()).isEqualTo("ARRIVED");
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
  assertThat(inBay()).isEmpty();
  // Closed, outside every zone: nothing more to do for it.
  assertThat(evacuation.evacuateAll(SITE)).noneMatch(s->s.vehicleId()==OHT);
 }

 @Test void theVehicleRefusedAtTheBranchGetsInOnceTheFinishedOneHasLeft(){
  finishedInTheBay();
  var refused=traffic.request(SITE,REVISION,PHANTOM,null,List.of(BRANCH,121L,BAY_PORT));
  assertThat(refused.reason()).isEqualTo("ZONE_FULL");
  assertThat(refused.nodeIds()).containsExactly(BRANCH);
  assertThat(stepOf(OHT).decision()).isEqualTo("EVACUATING");
  acknowledgeVehicle();
  standAt(111,true);
  traffic.advanced(SITE,OHT,111L);
  var admitted=traffic.request(SITE,REVISION,PHANTOM,null,List.of(BRANCH,121L,BAY_PORT));
  assertThat(admitted.nodeIds()).containsExactly(BRANCH,121L,BAY_PORT);
 }

 @Test void aVehicleWithAnOrderLeavesWithThatOrderInstead(){
  standAt(BAY_PORT,true);
  long id=orders.accept(SITE,new OrderRequest("EVAC",UUID.randomUUID().toString(),"OHT-E","OHT-C","C",50),"test",null).order().id();
  assignment.assign(SITE,id,"test",null);
  assertThat(stepOf(OHT).decision()).isEqualTo("HAS_ORDER");
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
 }

 @Test void aVehicleThatCannotBeCommandedIsLeftWhereItIs(){
  finishedInTheBay();
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '1 day' WHERE site_id=? AND vehicle_id=?",SITE,OHT);
  assertThat(stepOf(OHT).decision()).isEqualTo("POSITION_STALE");
  standAt(BAY_PORT,false);
  assertThat(stepOf(OHT).decision()).isEqualTo("VEHICLE_MOVING");
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
 }

 @Test void aVehicleTakenOutOfUseOrOutOfAutomaticWorkIsNotMovedOut(){
  finishedInTheBay();
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(stepOf(OHT).decision()).isEqualTo("VEHICLE_DISABLED");
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(stepOf(OHT).decision()).isEqualTo("ASSIGNMENT_DISABLED");
  assertThat(outbox.unresolvedForVehicle(SITE,OHT)).isEmpty();
  assertThat(ledger.relocatingVehicleIds(SITE)).doesNotContain(OHT);
 }

 @Test void theSiteSweepMovesItOutAfterOfferingNewWorkFirst(){
  finishedInTheBay();
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.evacuated()).anySatisfy(s->{
   assertThat(s.vehicleId()).isEqualTo(OHT);
   assertThat(s.decision()).isEqualTo("EVACUATING");
  });
 }
}
