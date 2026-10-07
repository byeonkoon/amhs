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
 private final com.amhs.equipment.SimulatedEquipment equipment; private final com.amhs.simulation.SimulatedLoad load;
 SimulationController(SimulatedVehicles vehicles,SiteSweep sweep,OperationOptionConfiguration options,
                      com.amhs.equipment.SimulatedEquipment equipment,com.amhs.simulation.SimulatedLoad load){
  this.vehicles=vehicles;this.sweep=sweep;this.options=options;
  this.equipment=equipment; this.load=load;
 }
 /** What a load run has seen: work written and refused, what was never to be seen, and how long the work took. */
 @GetMapping("/load")
 com.amhs.simulation.SimulatedLoad.Report load(@PathVariable long siteId){ return load.report(siteId); }
 @GetMapping("/equipment")
 List<com.amhs.equipment.SimulatedEquipment.State> equipment(@PathVariable long siteId){
  return equipment.list(siteId);
 }
 @PutMapping("/equipment/{unitId}/state")
 com.amhs.equipment.SimulatedEquipment.State equipmentState(@PathVariable long siteId,@PathVariable long unitId,
   @RequestBody com.amhs.equipment.SimulatedEquipment.Change change){
  return equipment.change(siteId,unitId,change);
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
  // Saying so is the whole point: a job half done is put where moving the vehicle leaves it, and
  // the caller has to have meant that rather than finding out afterwards.
  boolean releaseWork=Boolean.TRUE.equals(body.get("releaseWork"));
  return vehicles.place(siteId,vehicleId,n.longValue(),releaseWork);
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

 /**
  * Simulates a rail-powered vehicle picking its power up, or failing to.
  *
  * <p>A vehicle fed from the rail has no charging cycle, so the only way its buffer falls is that
  * it is not being fed. That is the condition worth being able to produce on purpose, rather than
  * waiting for a real fault to be the first time anyone sees what the controller does about it.
  */
 @PutMapping("/vehicles/{vehicleId}/power-pickup")
 Map<String,Object> powerPickup(@PathVariable long siteId,@PathVariable long vehicleId,
                                @RequestBody Map<String,Object> body){
  requireSimulation(siteId);
  Object healthy=body.get("healthy");
  if(!(healthy instanceof Boolean ok)) throw new IllegalArgumentException("healthy is required");
  vehicles.setPowerPickup(vehicleId,ok);
  return Map.of("vehicleId",vehicleId,"healthy",ok);
 }

 @GetMapping("/power-pickup-faults")
 Set<Long> pickupFaults(@PathVariable long siteId){ return vehicles.pickupFaults(); }

 @ExceptionHandler(IllegalArgumentException.class)
 org.springframework.http.ResponseEntity<Map<String,String>> rejected(IllegalArgumentException ex){
  return org.springframework.http.ResponseEntity.unprocessableEntity().body(Map.of("code",String.valueOf(ex.getMessage())));
 }
 /**
  * A refusal from the transport side is a refusal, not a fault. Placing a vehicle touches the work
  * it was doing, and that work can say no; letting it surface as a server error would tell the
  * caller the server broke when in fact it was answered.
  */
 @ExceptionHandler(com.amhs.transport.TransportRejected.class)
 org.springframework.http.ResponseEntity<Map<String,String>> rejected(com.amhs.transport.TransportRejected ex){
  return org.springframework.http.ResponseEntity
   .status(ex.isConflict()?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY)
   .body(Map.of("code",ex.code(),"message",String.valueOf(ex.getMessage())));
 }
}
