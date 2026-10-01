package com.amhs.simulation.api;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.transport.SiteSweep;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Driving the in-server simulator by hand. Exists only while the simulator is enabled. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/simulation")
@ConditionalOnProperty(name="amhs.simulation.enabled",havingValue="true")
class SimulationController {
 private final SimulatedVehicles vehicles; private final SiteSweep sweep;
 SimulationController(SimulatedVehicles vehicles,SiteSweep sweep){this.vehicles=vehicles;this.sweep=sweep;}

 /** Puts a vehicle on a node, online and idle, and starts simulating it. */
 @PostMapping("/vehicles/{vehicleId}/place")
 VehicleSnapshot place(@PathVariable long siteId,@PathVariable long vehicleId,@RequestBody Map<String,Object> body){
  Object node=body.get("nodeId");
  if(!(node instanceof Number n)) throw new IllegalArgumentException("nodeId is required");
  return vehicles.place(siteId,vehicleId,n.longValue());
 }

 /** One sweep of the site, then one step of every simulated vehicle. */
 @PostMapping("/tick")
 Map<String,Object> tick(@PathVariable long siteId){
  var pass=sweep.run(siteId,"simulator",null);
  var steps=vehicles.step(siteId);
  var out=new LinkedHashMap<String,Object>();
  out.put("sweep",pass); out.put("vehicles",steps);
  return out;
 }

 @GetMapping("/vehicles")
 Set<Long> placed(@PathVariable long siteId){ return vehicles.placed(); }
}
