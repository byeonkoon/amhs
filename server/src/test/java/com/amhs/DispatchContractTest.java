package com.amhs;
import com.amhs.dispatch.DispatchEvaluator;
import com.amhs.platform.VehicleType;
import com.amhs.topology.MapSnapshot;
import com.amhs.routing.RoutePlanner;
import com.amhs.routing.RouteResult;
import com.amhs.integration.CommunicationProfile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class DispatchContractTest {
 private MapSnapshot map() {
  return new MapSnapshot(1,1,List.of(new MapSnapshot.Node(1,"O1",0,0),new MapSnapshot.Node(2,"O2",1,0),new MapSnapshot.Node(3,"A1",0,2),new MapSnapshot.Node(4,"A2",1,2),new MapSnapshot.Node(5,"ISOLATED",9,9)),
  List.of(new MapSnapshot.Edge(1,1,2,10,true),new MapSnapshot.Edge(2,3,4,1,true)),
  List.of(new MapSnapshot.Port(1,"O-A","L",VehicleType.OHT,1),new MapSnapshot.Port(2,"O-B","U",VehicleType.OHT,2),new MapSnapshot.Port(3,"A-A","L",VehicleType.AGV,3)));
 }
 private DispatchEvaluator.Candidate vehicle(long id,VehicleType type,long node) { return new DispatchEvaluator.Candidate(id,type,node,true,true,true,true,true); }
 @Test void closerAgvIsExcludedAndUnreachableOhtDoesNotBlockOtherCandidates() {
  var result=new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(1,VehicleType.AGV,1),vehicle(2,VehicleType.OHT,5),vehicle(3,VehicleType.OHT,1)));
  assertThat(result.reason()).isEqualTo("PROPOSED"); assertThat(result.proposal().orElseThrow().vehicleId()).isEqualTo(3);
 }
 @Test void checksBothPortTypesBeforeDispatch() { assertThat(new DispatchEvaluator().evaluate(map(),1,3,List.of()).reason()).isEqualTo("PORT_TYPE_MISMATCH"); }
 @Test void disconnectedGraphsAndReverseDirectionAreUnreachable() {
  var r=new RoutePlanner();
  assertThat(r.find(map(),1,4).reason()).isEqualTo(RouteResult.Reason.NO_PATH);
  assertThat(r.find(map(),2,1).reason()).isEqualTo(RouteResult.Reason.NO_PATH);
  assertThat(r.find(map(),1,1).status()).isEqualTo(RouteResult.Status.SAME_NODE);
  assertThat(r.find(map(),1,1).costSeconds()).isZero();
 }
 @Test void offlineVehicleIsExcluded() {
  var v=new DispatchEvaluator.Candidate(1,VehicleType.OHT,1,true,true,false,true,true);
  assertThat(new DispatchEvaluator().evaluate(map(),1,2,List.of(v)).reason()).isEqualTo("NO_AVAILABLE_VEHICLE");
 }
 @Test void deterministicTieBreak() {
  var r=new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(8,VehicleType.OHT,1),vehicle(2,VehicleType.OHT,1)));
  assertThat(r.proposal().orElseThrow().vehicleId()).isEqualTo(2);
 }
 @Test void negativeCostsAreRejected() {
  var m=map(); var invalid=new MapSnapshot(1,1,m.nodes(),List.of(new MapSnapshot.Edge(1,1,2,-1,true)),m.ports());
  assertThatThrownBy(()->new RoutePlanner().find(invalid,1,2)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void missingVdaIdentityIsRejected() {
  assertThatThrownBy(()->new CommunicationProfile(CommunicationProfile.CommType.MQTT,CommunicationProfile.ProtoType.VDA5050,"3.0.0","SYNTHETIC",1,1,"",null)).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void unroutableDeliveryLegIsNotBlamedOnTheFleet() {
  var r=new DispatchEvaluator().evaluate(map(),2,1,List.of(vehicle(1,VehicleType.OHT,2)));
  assertThat(r.reason()).isEqualTo("DELIVERY_ROUTE_NO_PATH"); assertThat(r.proposal()).isEmpty();
 }
 @Test void sharedApproachFailureIsReportedWithItsReason() {
  assertThat(new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(1,VehicleType.OHT,5))).reason())
   .isEqualTo("APPROACH_ROUTE_NO_PATH");
  assertThat(new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(1,VehicleType.OHT,99))).reason())
   .isEqualTo("APPROACH_ROUTE_START_UNKNOWN");
 }
 @Test void mixedApproachFailuresStayGeneric() {
  assertThat(new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(1,VehicleType.OHT,5),vehicle(2,VehicleType.OHT,99))).reason())
   .isEqualTo("NO_REACHABLE_VEHICLE");
 }
 @Test void proposalCarriesOrderedSegmentsForBothLegs() {
  var p=new DispatchEvaluator().evaluate(map(),1,2,List.of(vehicle(7,VehicleType.OHT,1))).proposal().orElseThrow();
  assertThat(p.toSource().status()).isEqualTo(RouteResult.Status.SAME_NODE);
  assertThat(p.toDestination().edgeIds()).containsExactly(1L);
  assertThat(p.cost()).isEqualTo(10);
 }
}
