package com.amhs.simulation;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.CommandStatus;
import com.amhs.integration.DeviceCommand;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vehicles that exist only inside the server, for running the system without devices.
 *
 * <p>They speak the system's own vocabulary rather than imitating a wire protocol: they receive
 * movement commands with the nodes they are allowed to occupy, drive those nodes one per step, and
 * at a port they report what a load sensor would — carrier now on board, carrier now delivered —
 * as evidence. That keeps this simulator valid whatever the device protocols turn out to be.
 *
 * <p>Only vehicles explicitly placed are simulated. Their state lives in memory: a restart empties
 * the simulation, and the vehicles have to be placed again.
 */
public class SimulatedVehicles {
 private static final String ACTOR="simulator";
 private final FleetState fleet; private final TrafficControl traffic; private final CommandOutbox outbox;
 private final TransportLedger ledger; private final TransportOrders orders; private final TopologyQuery topology;
 private final ObjectMapper json;

 private record Sim(long siteId,long vehicleId,List<Long> authority,boolean carrier,boolean failNextTransfer){
  Sim withAuthority(List<Long> nodes){ return new Sim(siteId,vehicleId,List.copyOf(nodes),carrier,failNextTransfer); }
  Sim withCarrier(boolean on){ return new Sim(siteId,vehicleId,authority,on,failNextTransfer); }
  Sim failing(boolean fail){ return new Sim(siteId,vehicleId,authority,carrier,fail); }
 }
 private final Map<Long,Sim> vehicles=new ConcurrentHashMap<>();

 SimulatedVehicles(FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger,
                   TransportOrders orders,TopologyQuery topology,ObjectMapper json){
  this.fleet=fleet; this.traffic=traffic; this.outbox=outbox; this.ledger=ledger;
  this.orders=orders; this.topology=topology; this.json=json;
 }

 /** One step of what a vehicle did, for a screen or a test to read. */
 public record Step(long vehicleId,String action,Long nodeId){}

 /** Puts a vehicle on the track: online, under remote control, idle, empty. */
 public VehicleSnapshot place(long siteId,long vehicleId,long nodeId){
  vehicles.put(vehicleId,new Sim(siteId,vehicleId,List.of(),false,false));
  return report(siteId,vehicleId,nodeId,true,false);
 }
 public boolean isSimulated(long vehicleId){ return vehicles.containsKey(vehicleId); }
 /** Takes every vehicle out of the simulation. Their last reported state stays as it was. */
 public void clear(){ vehicles.clear(); }
 public Set<Long> placed(){ return Set.copyOf(vehicles.keySet()); }
 /** The next pickup or drop this vehicle attempts does not happen; the sensor says so. */
 public void failNextTransfer(long vehicleId){ vehicles.computeIfPresent(vehicleId,(id,s)->s.failing(true)); }

 /**
  * A command reaches the vehicle. A movement replaces what it may drive: the authorized nodes run
  * from where it stands. Anything else it receives is accepted and does nothing.
  */
 boolean receive(DeviceCommand command){
  if(command.vehicleId()==null) return false;
  var sim=vehicles.get(command.vehicleId());
  if(sim==null) return false;
  if("MOVE".equals(command.commandType())||"MORE".equals(command.commandType())){
   try{
    var nodes=new ArrayList<Long>();
    json.readTree(command.payload()).path("authorizedNodeIds").forEach(n->nodes.add(n.asLong()));
    vehicles.put(sim.vehicleId(),sim.withAuthority(nodes));
   }catch(Exception e){ return false; }
  }
  return true;
 }

 /** One step for every placed vehicle at the site: acknowledge, drive one node, or do one transfer step. */
 public List<Step> step(long siteId){
  long revisionId=ledger.activeRevision(siteId);
  var map=topology.getMap(siteId,revisionId);
  var portNode=new HashMap<Long,Long>();
  for(var port:map.ports()) portNode.put(port.id(),port.linkedNodeId());
  var orderOf=new HashMap<Long,Long>();
  for(var a:ledger.activeAssignments(siteId)) orderOf.put(a.vehicleId(),a.orderId());
  var out=new ArrayList<Step>();
  for(var sim:vehicles.values()){
   if(sim.siteId()!=siteId) continue;
   var state=fleet.find(siteId,revisionId,sim.vehicleId()).orElse(null);
   if(state==null||state.nodeId()==null) continue;
   // The device's acknowledgement arrives after the command went out, never with it.
   for(var c:outbox.unresolvedForVehicle(siteId,sim.vehicleId()))
    if(c.status()==CommandStatus.SENT) outbox.acknowledge(c.commandId());
   var order=Optional.ofNullable(orderOf.get(sim.vehicleId())).flatMap(id->ledger.find(siteId,id)).orElse(null);
   out.add(act(siteId,sim,state,order,portNode));
  }
  return out;
 }

 private Step act(long siteId,Sim sim,VehicleSnapshot state,TransportOrder order,Map<Long,Long> portNode){
  long at=state.nodeId();
  int i=sim.authority().indexOf(at);
  if(i>=0&&i<sim.authority().size()-1){
   long next=sim.authority().get(i+1);
   boolean last=i+1==sim.authority().size()-1;
   report(siteId,sim.vehicleId(),next,last,sim.carrier());
   traffic.advanced(siteId,sim.vehicleId(),next);
   // Moving for an order that was only assigned means it is on its way to the pickup.
   if(order!=null&&order.status()==OrderStatus.ASSIGNED) move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.system());
   return new Step(sim.vehicleId(),"MOVED",next);
  }
  // Standing at the end of what it may drive: idle. Keep the report fresh.
  report(siteId,sim.vehicleId(),at,true,sim.carrier());
  if(order==null) return new Step(sim.vehicleId(),"IDLE",at);
  Long source=portNode.get(order.sourcePortId()), destination=portNode.get(order.destinationPortId());
  switch(order.status()){
   case ASSIGNED -> { if(source!=null&&at==source){ move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"ARRIVED_AT_PICKUP",at);} }
   case TO_SOURCE -> { if(source!=null&&at==source){ move(siteId,order,OrderStatus.LOADING,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"PICKUP_STARTED",at);} }
   case LOADING -> {
    if(sim.failNextTransfer()){
     vehicles.put(sim.vehicleId(),sim.failing(false));
     move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.sensor(CarrierLocation.SOURCE));
     return new Step(sim.vehicleId(),"PICKUP_NOT_DONE",at);
    }
    vehicles.put(sim.vehicleId(),sim.withCarrier(true));
    report(siteId,sim.vehicleId(),at,true,true);
    move(siteId,order,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE));
    return new Step(sim.vehicleId(),"PICKED_UP",at);
   }
   case TO_DESTINATION -> { if(destination!=null&&at==destination){ move(siteId,order,OrderStatus.UNLOADING,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"DROP_STARTED",at);} }
   case UNLOADING -> {
    if(sim.failNextTransfer()){
     vehicles.put(sim.vehicleId(),sim.failing(false));
     move(siteId,order,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE));
     return new Step(sim.vehicleId(),"DROP_NOT_DONE",at);
    }
    vehicles.put(sim.vehicleId(),sim.withCarrier(false));
    report(siteId,sim.vehicleId(),at,true,false);
    move(siteId,order,OrderStatus.COMPLETED,TransitionEvidence.sensor(CarrierLocation.DESTINATION));
    return new Step(sim.vehicleId(),"DROPPED",at);
   }
   default -> {}
  }
  return new Step(sim.vehicleId(),"WAITING",at);
 }

 private void move(long siteId,TransportOrder order,OrderStatus to,TransitionEvidence evidence){
  orders.advance(siteId,order.id(),to,"simulated vehicle",ACTOR,null,evidence);
 }
 private VehicleSnapshot report(long siteId,long vehicleId,long nodeId,boolean idle,boolean carrier){
  long revisionId=ledger.activeRevision(siteId);
  return fleet.report(siteId,revisionId,vehicleId,new VehicleReport(nodeId,true,true,idle,carrier,"SIMULATED"));
 }
}
