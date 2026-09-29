package com.amhs;

import com.amhs.platform.ConfigurationException;
import com.amhs.platform.VehicleType;
import com.amhs.topology.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TopologyValidationTest {
 private final List<Object> events = new ArrayList<>();
 private MapSnapshot map(List<MapSnapshot.Port> ports) {
  return new MapSnapshot(1,1,
   List.of(new MapSnapshot.Node(1,"A",0,0),new MapSnapshot.Node(2,"B",1,0),new MapSnapshot.Node(3,"C",2,0)),
   List.of(new MapSnapshot.Edge(1,2,1,1,false)),ports);
 }
 private TopologyValidation validator(MapSnapshot map) {
  return new TopologyValidation((site,revision)->map,events::add);
 }
 @Test void disabledReverseEdgeStillPreventsMixingVehicleTypes() {
  var validation=validator(map(List.of(new MapSnapshot.Port(10,"P","LOAD","TEST",VehicleType.OHT,2))));
  assertThatThrownBy(()->validation.vehicleNodes(1,1,VehicleType.AGV,1,null,null))
   .isInstanceOf(ConfigurationException.class).hasMessage("VEHICLE_TYPE_NETWORK_MISMATCH");
 }
 @Test void disconnectedNetworkCanUseAnotherType() {
  var validation=validator(map(List.of(new MapSnapshot.Port(10,"P","LOAD","TEST",VehicleType.OHT,2))));
  assertThatCode(()->validation.vehicleNodes(1,1,VehicleType.AGV,3,null,null)).doesNotThrowAnyException();
 }
 @Test void homeAndChargeNodesMustAlsoMatchTheNetwork() {
  var validation=validator(map(List.of(new MapSnapshot.Port(10,"P","LOAD","TEST",VehicleType.OHT,2))));
  assertThatThrownBy(()->validation.vehicleNodes(1,1,VehicleType.AGV,3,1L,null)).hasMessage("VEHICLE_TYPE_NETWORK_MISMATCH");
  assertThatThrownBy(()->validation.vehicleNodes(1,1,VehicleType.AGV,3,null,999L)).hasMessage("NODE_OUTSIDE_REVISION");
 }
 @Test void portChangePublishesEntirePhysicalComponentForFleetValidation() {
  var validation=validator(map(List.of(new MapSnapshot.Port(10,"P","LOAD","TEST",VehicleType.OHT,2))));
  validation.portNode(1,1,1,VehicleType.AGV,10);
  assertThat(events).containsExactly(new PortChanging(1,1,VehicleType.AGV,Set.of(1L,2L)));
 }
 @Test void invalidPortDoesNotPublishChange() {
  var validation=validator(map(List.of()));
  assertThatThrownBy(()->validation.portNode(1,1,99,VehicleType.OHT,-1)).hasMessage("NODE_OUTSIDE_REVISION");
  assertThat(events).isEmpty();
 }
}
