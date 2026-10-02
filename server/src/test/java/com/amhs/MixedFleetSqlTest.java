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
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

/**
 * Two OHTs and two AGVs on their own tracks, run by the one transport, dispatch and traffic engine
 * in simulation: work goes only to the right kind, a first candidate that cannot get there gives
 * way to the next, vehicles, carriers and ports are never claimed twice, vehicles of one kind never
 * wait for the other kind, and every order finishes.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MixedFleetSqlTest {
 @Autowired SimulatedVehicles simulator; @Autowired SiteSweep sweep; @Autowired TransportOrders orders;
 @Autowired TransportAssignment assignment; @Autowired TransportLedger ledger; @Autowired TrafficControl traffic;
 @Autowired FleetState fleet; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT1=1, AGV1=2, OHT2=3, AGV2=4;
 private static final Map<Long,String> TYPE=Map.of(OHT1,"OHT",OHT2,"OHT",AGV1,"AGV",AGV2,"AGV");

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
  // Only what these cases change: the bay exit of the active map, which is open in the sample.
  jdbc.update("UPDATE topology.edge SET enabled=TRUE WHERE site_id=? AND revision_id=? AND from_node_id=123 AND to_node_id=110",SITE,REVISION);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=FALSE,home_node_id=NULL WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=FALSE,node_count=20,more_remaining_nodes=3,"
   +"more_min_remaining_nodes=1,run_mode='OPERATION' WHERE site_id=?",SITE);
 }
 /** All four in use and taking work, placed on their own tracks, the site simulated. */
 private void fourVehicles(long oht1,long oht2,long agv1,long agv2){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id IN (1,2,3,4)) WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION',node_count=6,more_remaining_nodes=2,"
   +"more_min_remaining_nodes=0 WHERE site_id=?",SITE);
  simulator.place(SITE,OHT1,oht1); simulator.place(SITE,OHT2,oht2);
  simulator.place(SITE,AGV1,agv1); simulator.place(SITE,AGV2,agv2);
 }
 private long accept(String from,String to){
  return tx.execute(s->orders.accept(SITE,new OrderRequest("MIX",UUID.randomUUID().toString(),from,to,
   "MIX-"+UUID.randomUUID(),50),"test",null).order().id());
 }
 private String typeOfNode(long nodeId){
  return jdbc.queryForObject("SELECT CASE WHEN code LIKE 'AGV%' THEN 'AGV' ELSE 'OHT' END FROM topology.node"
   +" WHERE site_id=? AND revision_id=? AND id=?",String.class,SITE,REVISION,nodeId);
 }

 /** What must hold after every step, whatever the order of events. */
 private void invariants(){
  var holds=traffic.held(SITE);
  // One vehicle per node, and only on its own track.
  var byNode=holds.stream().collect(Collectors.groupingBy(NodeHold::nodeId,Collectors.mapping(NodeHold::vehicleId,Collectors.toSet())));
  byNode.forEach((node,who)->{
   assertThat(who).as("node %s held by %s",node,who).hasSize(1);
   assertThat(typeOfNode(node)).as("node %s held by %s",node,who).isEqualTo(TYPE.get(who.iterator().next()));
  });
  for(var v:fleet.snapshot(SITE,REVISION)){
   if(v.nodeId()==null||!TYPE.containsKey(v.id())) continue;
   assertThat(typeOfNode(v.nodeId())).as("%s stands on %s",v.code(),v.nodeId()).isEqualTo(TYPE.get(v.id()));
  }
  // One order per vehicle, one vehicle per order, always the kind the order needs.
  var active=jdbc.queryForList("SELECT a.vehicle_id,a.order_id,o.vehicle_type FROM transport.assignment a"
   +" JOIN transport.order o ON o.id=a.order_id WHERE a.released_at IS NULL AND o.site_id=?",SITE);
  assertThat(active.stream().map(r->r.get("vehicle_id")).distinct().count()).isEqualTo(active.size());
  assertThat(active.stream().map(r->r.get("order_id")).distinct().count()).isEqualTo(active.size());
  for(var r:active) assertThat(TYPE.get(((Number)r.get("vehicle_id")).longValue())).isEqualTo(r.get("vehicle_type"));
  // A port used by one order holding a vehicle is not used by another one at the same time.
  var ports=jdbc.queryForList("SELECT o.id,o.status,o.source_port_id,o.destination_port_id FROM transport.order o"
   +" JOIN transport.assignment a ON a.order_id=o.id AND a.released_at IS NULL WHERE o.site_id=?",SITE);
  var users=new HashMap<Long,List<Object>>();
  for(var r:ports){
   String st=(String)r.get("status");
   if(List.of("ASSIGNED","TO_SOURCE","LOADING").contains(st))
    users.computeIfAbsent(((Number)r.get("source_port_id")).longValue(),k->new ArrayList<>()).add(r.get("id"));
   users.computeIfAbsent(((Number)r.get("destination_port_id")).longValue(),k->new ArrayList<>()).add(r.get("id"));
  }
  users.forEach((port,who)->assertThat(who).as("port %s used by orders %s",port,who).hasSize(1));
  // Separate tracks: nobody ever waits for a vehicle of the other kind.
  for(var w:jdbc.queryForList("SELECT vehicle_id,blocked_by_vehicle_id FROM traffic.reservation_wait WHERE site_id=?"
   +" AND blocked_by_vehicle_id IS NOT NULL",SITE)){
   long waiter=((Number)w.get("vehicle_id")).longValue(), blocker=((Number)w.get("blocked_by_vehicle_id")).longValue();
   if(TYPE.containsKey(waiter)&&TYPE.containsKey(blocker))
    assertThat(TYPE.get(blocker)).as("%s waits for %s",waiter,blocker).isEqualTo(TYPE.get(waiter));
  }
 }

 @Test void twoOfEachKindCarryTheirOwnWorkToTheEndWithoutClaimingAnythingTwice(){
  // AGV-02 waits past the drop point of the first AGV order, so neither has to drive through the
  // other one parked after its work (moving an idle vehicle out of the way is not built yet).
  fourVehicles(101L,112L,201L,208L);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE WHERE site_id=?",SITE);
  var ids=List.of(accept("OHT-A","OHT-C"),accept("OHT-D","OHT-B"),accept("AGV-A","AGV-B"),accept("AGV-C","AGV-A"));
  int ticks=0;
  SiteSweep.Sweep last=null;
  var firstSkips=new ArrayList<String>();
  for(;ticks<400;ticks++){
   last=sweep.run(SITE,"test",null);
   if(ticks<3) firstSkips.add(last.skipped().toString()+last.assigned());
   simulator.settle(SITE);
   simulator.drive(SITE,1.0);
   invariants();
   if(ids.stream().allMatch(id->orders.find(SITE,id).orElseThrow().status()==OrderStatus.COMPLETED)) break;
  }
  var why=last==null?"":last.skipped().toString();
  var history=ids.stream().map(id->id+":"+orders.find(SITE,id).orElseThrow().status()+"/"+jdbc.queryForList(
   "SELECT vehicle_id FROM transport.assignment WHERE order_id=? ORDER BY id",Long.class,id)).toList();
  why=why+" orders "+history+" first "+firstSkips;
  var where=fleet.snapshot(SITE,REVISION).stream().map(v->v.code()+"@"+v.nodeId()+":"+v.status()+(v.carrierPresent()?"+C":"")).toList();
  for(long id:ids) assertThat(orders.find(SITE,id).orElseThrow().status())
   .as("order %s after %s ticks; skipped %s; vehicles %s",id,ticks,why,where).isEqualTo(OrderStatus.COMPLETED);
  // Each order was carried by a vehicle of its own kind.
  for(var r:jdbc.queryForList("SELECT o.vehicle_type,a.vehicle_id FROM transport.assignment a JOIN transport.order o"
   +" ON o.id=a.order_id WHERE a.order_id IN ("+ids.stream().map(String::valueOf).collect(Collectors.joining(","))+")"))
   assertThat(TYPE.get(((Number)r.get("vehicle_id")).longValue())).isEqualTo(r.get("vehicle_type"));
  // The AGV order that drops where the other one picks up had to wait for that pickup.
  var waited=jdbc.queryForObject("SELECT count(*) FROM transport.assignment WHERE order_id=?",Integer.class,ids.get(3));
  assertThat(waited).isEqualTo(1);
 }

 @Test void workGoesOnlyToItsOwnKindEvenWhenTheOtherKindIsNearAndIdle(){
  fourVehicles(101L,112L,201L,210L);
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(v_type='AGV') WHERE site_id=?",SITE);
  long id=accept("OHT-A","OHT-C");
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,id,"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NO_AVAILABLE_VEHICLE"));
  assertThat(orders.find(SITE,id).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);
  assertThat(ledger.assignedVehicleIds(SITE)).doesNotContain(AGV1,AGV2);
 }

 @Test void aNearerVehicleThatCannotGetThereGivesWayToTheNextOne(){
  // OHT-02 stands in the bay, two nodes from the exit and close to OHT-D; with the exit closed it
  // cannot leave, so the far OHT-01 is the one that goes.
  fourVehicles(101L,122L,201L,210L);
  jdbc.update("UPDATE topology.edge SET enabled=FALSE WHERE site_id=? AND from_node_id=123 AND to_node_id=110",SITE);
  long id=accept("OHT-D","OHT-B");
  var assigned=tx.execute(s->assignment.assign(SITE,id,"test",null));
  assertThat(assigned.assignment().vehicleId()).isEqualTo(OHT1);
 }

 @Test void ordersAcrossKindsAreRefused(){
  assertThatThrownBy(()->accept("OHT-A","AGV-B"))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("ACCESS_VEHICLE_TYPE_MISMATCH"));
 }

 @Test void aCarrierStillOnItsWayCannotBeOrderedAgain(){
  String carrier="MIX-DUP-"+UUID.randomUUID();
  tx.execute(s->orders.accept(SITE,new OrderRequest("MIX",UUID.randomUUID().toString(),"OHT-A","OHT-C",carrier,50),"test",null));
  assertThatThrownBy(()->tx.execute(s->orders.accept(SITE,new OrderRequest("MIX",UUID.randomUUID().toString(),"OHT-D","OHT-B",carrier,50),"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("CARRIER_IN_TRANSPORT"));
 }

 @Test void aPortInUseKeepsTheNextOrderWaitingAndAVehicleWithACarrierTakesNoWork(){
  fourVehicles(101L,112L,201L,210L);
  long first=accept("OHT-A","OHT-C"), second=accept("OHT-D","OHT-C");
  tx.execute(s->assignment.assign(SITE,first,"test",null));
  // Same destination: the second waits while the first holds a vehicle for it.
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,second,"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,e->{
    assertThat(e.code()).isEqualTo("PORT_IN_USE");
    assertThat(e.isConflict()).isTrue();
   });
  // A vehicle carrying something is not offered new work, even when it is the only idle one.
  long third=accept("OHT-E","OHT-B");
  fleet.report(SITE,REVISION,OHT2,new VehicleReport(112L,true,true,true,true,"SIMULATED"));
  assertThatThrownBy(()->tx.execute(s->assignment.assign(SITE,third,"test",null)))
   .isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo("NO_AVAILABLE_VEHICLE"));
 }

 @Test void ordersNobodyCanTakeDoNotHideTheOnesBehindThem(){
  fourVehicles(101L,112L,201L,208L);
  // No OHT takes work; two hundred and one OHT orders come first, one AGV order after them.
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(v_type='AGV') WHERE site_id=?",SITE);
  jdbc.update("UPDATE platform.site_operation SET auto_dispatch=TRUE WHERE site_id=?",SITE);
  for(int i=0;i<201;i++) accept("OHT-A","OHT-C");
  long agv=accept("AGV-A","AGV-B");
  var pass=sweep.run(SITE,"test",null);
  assertThat(pass.assigned()).containsExactly(agv);
  // The OHT orders keep the real reason they wait for.
  assertThat(pass.skipped()).hasSize(201).allMatch(sk->sk.reason().equals("NO_AVAILABLE_VEHICLE"));
 }

 @Test void aSourcePortFreedByThePickupStaysFreeThroughCancelAndRecovery(){
  fourVehicles(101L,112L,201L,208L);
  long first=accept("OHT-A","OHT-C"), next=accept("OHT-A","OHT-B");
  tx.execute(s->assignment.assign(SITE,first,"test",null));
  tx.execute(s->orders.advance(SITE,first,OrderStatus.TO_SOURCE,"move","test",null));
  tx.execute(s->orders.advance(SITE,first,OrderStatus.LOADING,"pickup","test",null));
  tx.execute(s->orders.advance(SITE,first,OrderStatus.TO_DESTINATION,"picked up","test",null,
   TransitionEvidence.sensor(CarrierLocation.VEHICLE)));
  // Picked up and cancelled, then sent to recovery: the carrier is on the vehicle, not at OHT-A.
  tx.execute(s->orders.advance(SITE,first,OrderStatus.CANCEL_REQUESTED,"cancel after pickup","test",null));
  Boolean freeAfterCancel=tx.execute(s->!ledger.portInUse(SITE,next,orders.find(SITE,next).orElseThrow().sourcePortId(),
   orders.find(SITE,next).orElseThrow().destinationPortId()));
  assertThat(freeAfterCancel).isTrue();
  tx.execute(s->orders.advance(SITE,first,OrderStatus.RECOVERY_REQUIRED,"recovery","test",null));
  var assigned=tx.execute(s->assignment.assign(SITE,next,"test",null));
  assertThat(assigned.assignment().vehicleId()).isEqualTo(OHT2);
 }
}
