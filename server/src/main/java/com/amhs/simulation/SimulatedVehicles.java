package com.amhs.simulation;
import com.amhs.equipment.SimulatedEquipment;
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
 *
 * <p>The running loop drives them along each segment at their configured speed ({@link #drive}),
 * reporting where they are between nodes, and changes their node only when they arrive at the
 * next one. {@link #step} is the coarse form for tests and the manual tick: one node per call.
 */
public class SimulatedVehicles {
 private static final String ACTOR="simulator";
 private final FleetState fleet; private final TrafficControl traffic; private final CommandOutbox outbox;
 private final TransportLedger ledger; private final TransportOrders orders; private final TopologyQuery topology;
 private final ObjectMapper json;
 private final SimulatedEquipment equipment;
 private final com.amhs.platform.OperationOptionConfiguration options;

 /**
  * {@code segmentTo} and {@code travelled}: the node it is driving towards from the node it last
  * stood on, and how far along that segment it is, mm. Null and 0 while it stands.
  */
 private record Sim(long siteId,long vehicleId,List<Long> authority,boolean carrier,boolean failNextTransfer,
                    Long segmentTo,double travelled){
  Sim(long siteId,long vehicleId,List<Long> authority,boolean carrier,boolean failNextTransfer){
   this(siteId,vehicleId,authority,carrier,failNextTransfer,null,0);
  }
  Sim withAuthority(List<Long> nodes){ return new Sim(siteId,vehicleId,List.copyOf(nodes),carrier,failNextTransfer,segmentTo,travelled); }
  Sim withCarrier(boolean on){ return new Sim(siteId,vehicleId,authority,on,failNextTransfer,segmentTo,travelled); }
  Sim failing(boolean fail){ return new Sim(siteId,vehicleId,authority,carrier,fail,segmentTo,travelled); }
  Sim at(Long to,double along){ return new Sim(siteId,vehicleId,authority,carrier,failNextTransfer,to,along); }
 }
 private final Map<Long,Sim> vehicles=new ConcurrentHashMap<>();
 /**
  * State of charge per simulated vehicle, percent: it runs down while driving (and slowly while
  * standing) and up while charging. Charge commands received wait here for the next step.
  */
 private final Map<Long,Double> battery=new ConcurrentHashMap<>();
 private final Map<Long,String> chargeOrder=new ConcurrentHashMap<>();
 /**
  * Vehicles simulating a failure to take power from the rail.
  *
  * <p>A rail-powered vehicle's store is a buffer, kept full by the track; it is not a traction
  * battery and has no charging cycle. The way it goes down is the vehicle not picking the power up
  * -- a pickup, a rail section, a vehicle-side circuit -- and that is what this stands in for, so
  * the condition can be tried out without a fault being the only way to see it.
  */
 private final java.util.Set<Long> pickupFaulted=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private static final double DRAIN_DRIVING=0.25, DRAIN_STANDING=0.01, CHARGE_RATE=1.5;
 /** How fast the rail refills a buffer that is being fed, and how fast one that is not runs down. */
 private static final double BUFFER_REFILL=5.0, BUFFER_DRAIN=0.35;
 /**
  * Held while a vehicle is moved or its state is changed, so a change somebody makes between two
  * motion ticks is not overwritten by a tick that read the vehicle before it.
  */
 private final Object motion=new Object();
 /** The map each motion tick drives on, read again at least every two seconds so edits show up. */
 private record CachedMap(com.amhs.topology.MapSnapshot map,long readAt){}
 private final Map<Long,CachedMap> maps=new ConcurrentHashMap<>();

 SimulatedVehicles(FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger,
                   TransportOrders orders,TopologyQuery topology,ObjectMapper json,SimulatedEquipment equipment,
                   com.amhs.platform.OperationOptionConfiguration options){
  this.fleet=fleet; this.traffic=traffic; this.outbox=outbox; this.ledger=ledger;
  this.orders=orders; this.topology=topology; this.json=json;
  this.equipment=equipment; this.options=options;
 }

 /** One step of what a vehicle did, for a screen or a test to read. */
 public record Step(long vehicleId,String action,Long nodeId){}

 /** Whether this vehicle carries its own charge, or is fed from the rail and carries a buffer. */
 private static boolean charges(VehicleSnapshot state){
  return state.chargingPolicy()!=null&&!"DISABLED".equals(state.chargingPolicy());
 }

 /**
  * Simulates a rail-powered vehicle picking its power up, or failing to.
  *
  * <p>Failing is the condition worth being able to produce: the buffer then only falls, which is
  * what tells a controller the vehicle is not being fed and should come off the main line before it
  * stops somewhere that blocks everything behind it.
  */
 public void setPowerPickup(long vehicleId,boolean healthy){
  if(healthy) pickupFaulted.remove(vehicleId); else pickupFaulted.add(vehicleId);
 }
 /** Which vehicles are simulating a pickup failure; for a screen or a test to read. */
 public java.util.Set<Long> pickupFaults(){ return java.util.Set.copyOf(pickupFaulted); }

 /** Puts a vehicle on the track: online, under remote control, idle, empty. */
 public VehicleSnapshot place(long siteId,long vehicleId,long nodeId){
  synchronized(motion){
   vehicles.put(vehicleId,new Sim(siteId,vehicleId,List.of(),false,false));
   battery.putIfAbsent(vehicleId,100.0);
   var placed=report(siteId,vehicleId,nodeId,"IDLE",false);
   fleet.reportBattery(siteId,ledger.activeRevision(siteId),vehicleId,battery.get(vehicleId));
   return fleet.find(siteId,ledger.activeRevision(siteId),vehicleId).orElse(placed);
  }
 }

 /**
  * Changes only the mode and status of a simulated vehicle, the way an operator at the vehicle
  * would: where it is and whether it carries stay exactly as they are now, also when it is between
  * two nodes. Serialised with the motion, so a tick cannot put back an older position.
  */
 public VehicleSnapshot setState(long siteId,long vehicleId,String mode,String status){
  synchronized(motion){
   var sim=vehicles.get(vehicleId);
   if(sim==null||sim.siteId()!=siteId) throw new IllegalArgumentException("NOT_A_SIMULATED_VEHICLE");
   long revisionId=ledger.activeRevision(siteId);
   var state=fleet.find(siteId,revisionId,vehicleId).orElseThrow(()->new IllegalArgumentException("VEHICLE_NOT_FOUND"));
   if(state.nodeId()==null) throw new IllegalArgumentException("POSITION_UNKNOWN");
   // One statement: the place it is in, between nodes or on one, is never cleared on the way.
   // Given back to the controller between two nodes it carries on driving, so it is stored moving.
   return fleet.reportModeAndStatus(siteId,revisionId,vehicleId,mode,status);
  }
 }
 public boolean isSimulated(long vehicleId){ return vehicles.containsKey(vehicleId); }
 /** Takes every vehicle out of the simulation. Their last reported state stays as it was. */
 public void clear(){ vehicles.clear(); maps.clear(); battery.clear(); chargeOrder.clear(); pickupFaulted.clear(); }

 /** Sets a simulated vehicle's state of charge, as if it reported it; for trying charging out. */
 public VehicleSnapshot setBattery(long siteId,long vehicleId,double pct){
  synchronized(motion){
   var sim=vehicles.get(vehicleId);
   if(sim==null||sim.siteId()!=siteId) throw new IllegalArgumentException("NOT_A_SIMULATED_VEHICLE");
   if(!Double.isFinite(pct)||pct<0||pct>100) throw new IllegalArgumentException("INVALID_BATTERY");
   battery.put(vehicleId,pct);
   long revisionId=ledger.activeRevision(siteId);
   fleet.reportBattery(siteId,revisionId,vehicleId,pct);
   return fleet.find(siteId,revisionId,vehicleId).orElseThrow();
  }
 }
 /** Stops simulating vehicles of sites that are no longer in simulation. */
 public void clearExcept(java.util.Set<Long> sites){
  vehicles.values().removeIf(v->!sites.contains(v.siteId()));
  pickupFaulted.removeIf(id->!vehicles.containsKey(id));
 }

 /**
  * Puts every vehicle in use at the site on the track that is not simulated yet: where it last
  * reported if anyone did, otherwise at its configured initial node.
  */
 public void placeAll(long siteId,com.amhs.fleet.VehicleConfiguration configuration){
  long revisionId=ledger.activeRevision(siteId);
  var reported=new HashMap<Long,VehicleSnapshot>();
  for(var v:fleet.snapshot(siteId,revisionId)) reported.put(v.id(),v);
  for(var record:configuration.list(siteId)){
   var settings=record.settings();
   if(!settings.enabled()||settings.revisionId()!=revisionId||vehicles.containsKey(record.id())) continue;
   var last=reported.get(record.id());
   long node=last!=null&&last.nodeId()!=null?last.nodeId():settings.initialNodeId();
   vehicles.put(record.id(),new Sim(siteId,record.id(),List.of(),last!=null&&last.carrierPresent(),false));
   battery.put(record.id(),last!=null&&last.batteryPct()!=null?last.batteryPct():100.0);
   report(siteId,record.id(),node,"IDLE",last!=null&&last.carrierPresent());
   fleet.reportBattery(siteId,revisionId,record.id(),battery.get(record.id()));
  }
 }
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
    // Only the authority is replaced; how far along its segment the vehicle is stays its own.
    vehicles.computeIfPresent(sim.vehicleId(),(id,s)->s.withAuthority(nodes));
   }catch(Exception e){ return false; }
  }
  // Starting or stopping a charge is carried out on the vehicle's next step.
  if("CHARGE_START".equals(command.commandType())||"CHARGE_STOP".equals(command.commandType()))
   chargeOrder.put(sim.vehicleId(),command.commandType());
  if("STOP".equals(command.commandType())){
   // Stops at the end of the segment it is on; standing, it goes nowhere. It then reports idle.
   vehicles.computeIfPresent(sim.vehicleId(),(id,s)->{
    int j=s.segmentTo()==null?-1:s.authority().indexOf(s.segmentTo());
    return s.withAuthority(j<0?List.of():s.authority().subList(0,j+1));
   });
  }
  return true;
 }

 /**
  * Drives every placed vehicle of the site for {@code seconds} at its configured speed along its
  * authority. Between nodes it reports where it is; arriving at a node, it reports that node and
  * frees what lies behind. A vehicle held by its mode or status does not move.
  */
 public void drive(long siteId,double seconds){
  if(!options.get(siteId).settings().simulated()) return;
  synchronized(motion){
   long revisionId=ledger.activeRevision(siteId);
   var map=mapOf(siteId,revisionId);
   var orderOf=new HashMap<Long,Long>();
   for(var a:ledger.activeAssignments(siteId)) orderOf.put(a.vehicleId(),a.orderId());
   for(var sim:List.copyOf(vehicles.values())){
    if(sim.siteId()!=siteId) continue;
    var state=fleet.find(siteId,revisionId,sim.vehicleId()).orElse(null);
    if(state==null||state.nodeId()==null) continue;
    String status=state.effectiveStatus();
    // Two different things are being modelled, and running them together is what made a vehicle
    // that cannot charge report an empty battery. A vehicle with a charging policy carries its own
    // charge: it runs down driving, slower standing, and up while charging. A vehicle without one
    // is powered from the rail, and what it carries is a buffer the rail keeps full -- it only
    // falls when the power is not being picked up, which is a fault and not a duty cycle.
    double rate=charges(state)
     ?("CHARGING".equals(status)?CHARGE_RATE
       :("MOVING".equals(status)||state.betweenNodes())?-DRAIN_DRIVING:-DRAIN_STANDING)
     :(pickupFaulted.contains(sim.vehicleId())?-BUFFER_DRAIN:BUFFER_REFILL);
    battery.compute(sim.vehicleId(),(id,b)->Math.max(0,Math.min(100,(b==null?100:b)+rate*seconds)));
    if(held(state)) continue;
    driveOne(siteId,revisionId,map,sim.vehicleId(),state,seconds,orderOf.get(sim.vehicleId()));
   }
  }
 }

 private void driveOne(long siteId,long revisionId,com.amhs.topology.MapSnapshot map,long vehicleId,VehicleSnapshot state,
                       double seconds,Long orderId){
  var sim=vehicles.get(vehicleId);
  long at=state.nodeId();
  double budget=Math.max(50,state.speedMmS())*seconds;
  while(true){
   var authority=sim.authority();
   int i=authority.indexOf(at);
   if(i<0||i>=authority.size()-1) break;
   long next=authority.get(i+1);
   // A new authority that turns elsewhere starts the segment again from the node it stood on.
   double along=Objects.equals(sim.segmentTo(),next)?sim.travelled():0;
   var shape=com.amhs.topology.SegmentShape.between(map,at,next);
   double length=shape==null?0:shape.length();
   double reach=along+budget;
   if(reach<length){
    // Driving between nodes is always reported as moving, also when it resumes part way along.
    double[] p=shape.atDistance(reach);
    fleet.reportDriving(siteId,revisionId,vehicleId,p[0],p[1]);
    vehicles.computeIfPresent(vehicleId,(id,s)->s.at(next,reach));
    return;
   }
   budget-=length-along;
   boolean last=i+1==authority.size()-1;
   report(siteId,vehicleId,next,last?"IDLE":"MOVING",sim.carrier());
   traffic.advanced(siteId,vehicleId,next);
   sim=vehicles.computeIfPresent(vehicleId,(id,s)->s.at(null,0));
   if(orderId!=null){
    var order=ledger.find(siteId,orderId).orElse(null);
    if(order!=null&&order.status()==OrderStatus.ASSIGNED) move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.system());
   }
   at=next;
   if(sim==null) return;
  }
 }

 private com.amhs.topology.MapSnapshot mapOf(long siteId,long revisionId){
  var cached=maps.get(siteId);
  long now=System.nanoTime();
  if(cached==null||cached.map().revisionId()!=revisionId||now-cached.readAt()>2_000_000_000L){
   cached=new CachedMap(topology.getMap(siteId,revisionId),now);
   maps.put(siteId,cached);
  }
  return cached.map();
 }

 /** Somebody switched it to manual or semi-auto, or it reported a fault, a pause, charging or a reset. */
 private static boolean held(VehicleSnapshot state){
  String mode=state.mode()==null?"REMOTE":state.mode();
  return !"REMOTE".equals(mode)||HOLD.contains(state.effectiveStatus());
 }

 /** One step for every placed vehicle at the site: acknowledge, drive one node, or do one transfer step. */
 public List<Step> step(long siteId){ return step(siteId,true); }

 /**
  * What the running loop does between drives: acknowledge, and for a vehicle standing at the end of
  * its authority, the next transfer step. Driving itself is left to {@link #drive}.
  */
 public List<Step> settle(long siteId){ return step(siteId,false); }

 private List<Step> step(long siteId,boolean jump){
  synchronized(motion){
  if(!options.get(siteId).settings().simulated()) return List.of();
  maps.remove(siteId);
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
   var charge=battery.get(sim.vehicleId());
   if(charge!=null) fleet.reportBattery(siteId,revisionId,sim.vehicleId(),Math.round(charge*10)/10.0);
   // A charge command received since the last step: start charging where it stands, or stop.
   // Its control mode is never changed by it: a vehicle somebody switched to manual stays manual.
   String chargeCommand=chargeOrder.remove(sim.vehicleId());
   if(chargeCommand!=null&&!state.betweenNodes()){
    String mode=state.mode()==null?"REMOTE":state.mode();
    boolean start="CHARGE_START".equals(chargeCommand);
    if(start&&(!"REMOTE".equals(mode)||!"IDLE".equals(state.effectiveStatus()))){
     // Checked again when it would act: only a vehicle under remote control standing idle starts.
     out.add(new Step(sim.vehicleId(),"CHARGE_START_REFUSED",state.nodeId()));
     continue;
    }
    if(!start&&!"CHARGING".equals(state.effectiveStatus())){
     out.add(new Step(sim.vehicleId(),"NOT_CHARGING",state.nodeId()));
     continue;
    }
    fleet.reportModeAndStatus(siteId,revisionId,sim.vehicleId(),mode,start?"CHARGING":"IDLE");
    out.add(new Step(sim.vehicleId(),start?"CHARGING_STARTED":"CHARGING_STOPPED",state.nodeId()));
    continue;
   }
   var order=Optional.ofNullable(orderOf.get(sim.vehicleId())).flatMap(id->ledger.find(siteId,id)).orElse(null);
   out.add(act(siteId,vehicles.getOrDefault(sim.vehicleId(),sim),state,order,portNode,jump));
  }
  return out;
  }
 }

 /** Statuses in which a vehicle does not drive or transfer until told otherwise. */
 private static final Set<String> HOLD=Set.of("ERROR","PAUSED","RESET","CHARGING");

 private Step act(long siteId,Sim sim,VehicleSnapshot state,TransportOrder order,Map<Long,Long> portNode,boolean jump){
  long at=state.nodeId();
  // Somebody switched it to manual or semi-auto, or reported it faulted, paused, charging or
  // resetting: it stays put and keeps saying so until it is reported back to remote and idle.
  String mode=state.mode()==null?"REMOTE":state.mode();
  if(held(state)){
   // Saying again that it is held, in one statement that keeps where it is, between nodes too.
   fleet.reportModeAndStatus(siteId,ledger.activeRevision(siteId),sim.vehicleId(),mode,state.effectiveStatus());
   return new Step(sim.vehicleId(),"HELD_"+(!"REMOTE".equals(mode)?mode:state.effectiveStatus()),at);
  }
  int i=sim.authority().indexOf(at);
  if(i>=0&&i<sim.authority().size()-1&&!jump) return new Step(sim.vehicleId(),"DRIVING",at);
  if(i>=0&&i<sim.authority().size()-1){
   vehicles.computeIfPresent(sim.vehicleId(),(id,s)->s.at(null,0));
   long next=sim.authority().get(i+1);
   boolean last=i+1==sim.authority().size()-1;
   report(siteId,sim.vehicleId(),next,last?"IDLE":"MOVING",sim.carrier());
   traffic.advanced(siteId,sim.vehicleId(),next);
   // Moving for an order that was only assigned means it is on its way to the pickup.
   if(order!=null&&order.status()==OrderStatus.ASSIGNED) move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.system());
   return new Step(sim.vehicleId(),"MOVED",next);
  }
  // Standing at the end of what it may drive: idle. Keep the report fresh.
  String standing=order==null?"IDLE":order.status()==OrderStatus.LOADING?"LOADING"
   :order.status()==OrderStatus.UNLOADING?"UNLOADING":"IDLE";
  report(siteId,sim.vehicleId(),at,standing,sim.carrier());
  if(order==null) return new Step(sim.vehicleId(),"IDLE",at);
  String equipmentStatus=switch(order.status()){
   case TO_SOURCE -> "LOADING";
   case TO_DESTINATION -> "UNLOADING";
   case LOADING,UNLOADING -> "IDLE";
   default -> null;
  };
  long port=order.status()==OrderStatus.TO_SOURCE||order.status()==OrderStatus.LOADING
   ?order.sourcePortId():order.destinationPortId();
  if(equipmentStatus!=null&&!Objects.equals(portNode.get(port),at))
   return new Step(sim.vehicleId(),"WAITING_FOR_PORT",at);
  if(equipmentStatus!=null&&Objects.equals(portNode.get(port),at)){
   try { return equipment.transfer(siteId,port,equipmentStatus,()->transferStep(siteId,sim,at,order,portNode)); }
   catch(SimulatedEquipment.Prohibited e){
    return new Step(sim.vehicleId(),e.getMessage(),at);
   }
  }
  return transferStep(siteId,sim,at,order,portNode);
 }

 private Step transferStep(long siteId,Sim sim,long at,TransportOrder order,Map<Long,Long> portNode){
  Long source=portNode.get(order.sourcePortId()), destination=portNode.get(order.destinationPortId());
  switch(order.status()){
   case ASSIGNED -> { if(source!=null&&at==source){ move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"ARRIVED_AT_PICKUP",at);} }
   case TO_SOURCE -> { if(source!=null&&at==source){ report(siteId,sim.vehicleId(),at,"LOADING",sim.carrier());
    move(siteId,order,OrderStatus.LOADING,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"PICKUP_STARTED",at);} }
   case LOADING -> {
    if(sim.failNextTransfer()){
     vehicles.put(sim.vehicleId(),sim.failing(false));
     move(siteId,order,OrderStatus.TO_SOURCE,TransitionEvidence.sensor(CarrierLocation.SOURCE));
     return new Step(sim.vehicleId(),"PICKUP_NOT_DONE",at);
    }
    move(siteId,order,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE));
    vehicles.put(sim.vehicleId(),sim.withCarrier(true));
    report(siteId,sim.vehicleId(),at,"IDLE",true);
    return new Step(sim.vehicleId(),"PICKED_UP",at);
   }
   case TO_DESTINATION -> { if(destination!=null&&at==destination){ report(siteId,sim.vehicleId(),at,"UNLOADING",sim.carrier());
    move(siteId,order,OrderStatus.UNLOADING,TransitionEvidence.system());
    return new Step(sim.vehicleId(),"DROP_STARTED",at);} }
   case UNLOADING -> {
    if(sim.failNextTransfer()){
     vehicles.put(sim.vehicleId(),sim.failing(false));
     move(siteId,order,OrderStatus.TO_DESTINATION,TransitionEvidence.sensor(CarrierLocation.VEHICLE));
     return new Step(sim.vehicleId(),"DROP_NOT_DONE",at);
    }
    move(siteId,order,OrderStatus.COMPLETED,TransitionEvidence.sensor(CarrierLocation.DESTINATION));
    vehicles.put(sim.vehicleId(),sim.withCarrier(false));
    report(siteId,sim.vehicleId(),at,"IDLE",false);
    return new Step(sim.vehicleId(),"DROPPED",at);
   }
   default -> {}
  }
  return new Step(sim.vehicleId(),"WAITING",at);
 }

 private void move(long siteId,TransportOrder order,OrderStatus to,TransitionEvidence evidence){
  orders.advance(siteId,order.id(),to,"simulated vehicle",ACTOR,null,evidence);
 }
 /** Reports the way a vehicle does: remote mode, a status on its own axis, and whether it carries. */
 private VehicleSnapshot report(long siteId,long vehicleId,long nodeId,String status,boolean carrier){
  return reportAs(siteId,vehicleId,nodeId,"REMOTE",status,carrier);
 }
 private VehicleSnapshot reportAs(long siteId,long vehicleId,long nodeId,String mode,String status,boolean carrier){
  long revisionId=ledger.activeRevision(siteId);
  return fleet.report(siteId,revisionId,vehicleId,
   new VehicleReport(nodeId,true,true,false,carrier,"SIMULATED",null,mode,status));
 }
}
