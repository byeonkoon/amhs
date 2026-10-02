package com.amhs.simulation.api;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.simulation.SimulatedVehicles;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.transport.SiteSweep;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Driving the in-server simulator by hand, for a site whose run mode is SIMULATION. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/simulation")
class SimulationController {
 private final SimulatedVehicles vehicles; private final SiteSweep sweep; private final OperationOptionConfiguration options;
 SimulationController(SimulatedVehicles vehicles,SiteSweep sweep,OperationOptionConfiguration options){
  this.vehicles=vehicles;this.sweep=sweep;this.options=options;
 }
 /** A site on real devices is never given simulated positions or simulated steps. */
 private void requireSimulation(long siteId){
  if(!options.get(siteId).settings().simulated())
   throw new ResponseStatusException(HttpStatus.CONFLICT,"SITE_NOT_IN_SIMULATION");
 }

 /** Puts a vehicle on a node, online and idle, and starts simulating it. */
 @PostMapping("/vehicles/{vehicleId}/place")
 VehicleSnapshot place(@PathVariable long siteId,@PathVariable long vehicleId,@RequestBody Map<String,Object> body){
  requireSimulation(siteId);
  Object node=body.get("nodeId");
  if(!(node instanceof Number n)) throw new IllegalArgumentException("nodeId is required");
  return vehicles.place(siteId,vehicleId,n.longValue());
 }

 /** One sweep of the site, then one step of every simulated vehicle. */
 @PostMapping("/tick")
 Map<String,Object> tick(@PathVariable long siteId){
  requireSimulation(siteId);
  var pass=sweep.run(siteId,"simulator",null);
  var steps=vehicles.step(siteId);
  var out=new LinkedHashMap<String,Object>();
  out.put("sweep",pass); out.put("vehicles",steps);
  return out;
 }

 /**
  * Changes a simulated vehicle's mode and status only, as if set at the vehicle. Its position and
  * load are kept as the simulator has them, never taken from the request.
  */
 @PutMapping("/vehicles/{vehicleId}/state")
 VehicleSnapshot state(@PathVariable long siteId,@PathVariable long vehicleId,@RequestBody Map<String,Object> body){
  requireSimulation(siteId);
  Object mode=body.get("mode"), status=body.get("status"), charge=body.get("batteryPct");
  // A state of charge may come alone, to try charging out; mode and status come together.
  if(charge instanceof Number pct&&mode==null&&status==null) return vehicles.setBattery(siteId,vehicleId,pct.doubleValue());
  if(!(mode instanceof String m)||!(status instanceof String st)) throw new IllegalArgumentException("mode and status are required");
  var stored=vehicles.setState(siteId,vehicleId,m,st);
  return charge instanceof Number pct?vehicles.setBattery(siteId,vehicleId,pct.doubleValue()):stored;
 }

 @GetMapping("/vehicles")
 Set<Long> placed(@PathVariable long siteId){ return vehicles.placed(); }

 @ExceptionHandler(IllegalArgumentException.class)
 org.springframework.http.ResponseEntity<Map<String,String>> rejected(IllegalArgumentException ex){
  return org.springframework.http.ResponseEntity.unprocessableEntity().body(Map.of("code",String.valueOf(ex.getMessage())));
 }
}
