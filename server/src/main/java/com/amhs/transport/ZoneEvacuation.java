package com.amhs.transport;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.routing.RoutePlanner;
import com.amhs.routing.RouteResult;
import com.amhs.topology.MapSnapshot;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.NodeHold;
import com.amhs.traffic.ReservationGrant;
import com.amhs.traffic.TrafficControl;
import com.amhs.traffic.ZoneOccupancy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Moves a vehicle out of a zone once its work there is done.
 *
 * <p>A zone admits only so many vehicles. One that finished at a port inside it and then simply
 * stayed would keep a place it no longer needs, while the vehicles refused entry go round the loop
 * waiting for it. So an idle vehicle with no order, standing in a zone, is given a move out: to its
 * configured home node when that is outside every zone, free and reachable, otherwise to the nearest
 * place outside where it is not in the way: in no zone, not a merge point, not a port, not where
 * another vehicle stands or holds.
 *
 * <p>The same machinery carries an operator's move: a vehicle sent to a chosen node by hand
 * ({@link #moveTo}). It is opened only for a vehicle that is in use, under remote control, idle and
 * holding no order, the conditions the existing system checks before a manual move.
 *
 * <p>The move carries no transport order, so its command belongs to the vehicle alone. While it is
 * open the vehicle is not offered to dispatch. It is continued past congestion the way an order's
 * route is: only once the vehicle has used what it was given, and never while an earlier command
 * for it is unsettled. It closes when the vehicle stands on the place it was sent to.
 */
@Service
public class ZoneEvacuation {
 private final TransportLedger ledger; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox; private final TopologyQuery topology;
 private final OperationOptionConfiguration operations; private final ObjectMapper json;
 private final RoutePlanner planner=new RoutePlanner();
 public ZoneEvacuation(TransportLedger ledger,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,
                       TopologyQuery topology,OperationOptionConfiguration operations,ObjectMapper json){
  this.ledger=ledger; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox;
  this.topology=topology; this.operations=operations; this.json=json;
 }

 /** What one vehicle's turn decided; {@code commandId} is set only when a movement was written. */
 public record Step(long vehicleId,String decision,List<Long> addedNodeIds,String commandId){
  public Step { addedNodeIds=List.copyOf(addedNodeIds); }
 }

 /** One pass over every vehicle standing in a zone or already being moved out of one. */
 @Transactional
 public List<Step> evacuateAll(long siteId){
  long revisionId=ledger.activeRevision(siteId);
  var zoneOf=new HashMap<Long,Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId))
   if(zone.enabled()&&zone.revisionId()==revisionId) for(Long node:zone.nodeIds()) zoneOf.put(node,zone.id());
  var relocating=new HashSet<>(ledger.relocatingVehicleIds(siteId));
  var vehicles=fleet.snapshot(siteId,revisionId).stream()
   .sorted(Comparator.comparingLong(VehicleSnapshot::id)).toList();
  var cutoff=OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());

  // The read above only picks who to look at. Each one is decided again under its own lock, in
  // ascending id order like every other vehicle lock, so two passes cannot trap each other.
  var out=new ArrayList<Step>();
  for(var vehicle:vehicles){
   boolean inZone=vehicle.nodeId()!=null&&zoneOf.containsKey(vehicle.nodeId());
   if(!inZone&&!relocating.contains(vehicle.id())) continue;
   out.add(step(siteId,revisionId,vehicle.id(),zoneOf,vehicles,cutoff));
  }
  return out;
 }

 private Step step(long siteId,long revisionId,long vehicleId,Map<Long,Long> zoneOf,
                   List<VehicleSnapshot> vehicles,OffsetDateTime cutoff){
  // Dispatch takes this same lock before holding a vehicle, so a move out and new work cannot both
  // claim it: whichever commits first is what the other sees once it gets the lock.
  fleet.lockForAssignment(siteId,List.of(vehicleId));
  var vehicle=fleet.find(siteId,revisionId,vehicleId).orElse(null);
  if(vehicle==null) return none(vehicleId,"VEHICLE_NOT_FOUND");
  var relocation=ledger.openRelocations(siteId).stream().filter(r->r.vehicleId()==vehicleId).findFirst().orElse(null);
  Long zone=vehicle.nodeId()==null?null:zoneOf.get(vehicle.nodeId());
  if(relocation==null&&zone==null) return none(vehicleId,"NOT_IN_ZONE");
  // A vehicle holding an order leaves with that order; a second movement would cross it.
  if(ledger.assignedVehicleIds(siteId).contains(vehicleId)){
   if(relocation!=null) ledger.finishRelocation(relocation.id(),"ORDER_TOOK_OVER");
   return none(vehicleId,"HAS_ORDER");
  }
  // Taken out of use, or out of automatic work, by somebody: it is not moved on the controller's
  // own initiative either. It stays where it is until an operator moves it or turns it back on.
  // An operator's own move is the operator deciding, so only taking it out of use stops that.
  boolean manual=relocation!=null&&Relocation.MANUAL.equals(relocation.purpose());
  // Taken out of use or out of automatic work, or an operator asked the move to stop: whichever
  // came, the move is wound down the same way.
  String decision=!vehicle.enabled()?"VEHICLE_DISABLED"
   :!manual&&!vehicle.assignmentEnabled()?"ASSIGNMENT_DISABLED"
   :relocation!=null&&relocation.stopRequestedAt()!=null?"MOVE_STOPPED":null;
  if(decision!=null){
   if(relocation!=null){
    // The move stops here: what never left is taken back and nothing new is sent. If nothing of
    // this move ever reached the vehicle, it never started and the move simply ends. Otherwise the
    // vehicle may be driving on what it was given, so the move keeps its road until the vehicle
    // answers: online, standing idle on a known node, in a fresh report made after the stop was
    // decided. An acknowledgement or an older idle report is not that answer.
    var stopAt=ledger.requestRelocationStop(relocation.id());
    var withdrawal=outbox.withdrawForVehicle(siteId,vehicleId,decision,relocation.startedAt());
    String stopCommand="relocation-"+relocation.id()+"-stop";
    if(withdrawal.delivered()){
     // Tell the vehicle to stop where it can, once: the same id is not queued twice. What a STOP
     // is on the wire is the communication contract's to decide; a simulated vehicle stops at the
     // end of the segment it is on.
     outbox.enqueueForVehicle(siteId,vehicleId,stopCommand,"STOP",
      "{\"relocationId\":"+relocation.id()+",\"vehicleId\":"+vehicleId+",\"reason\":\""+decision+"\"}");
     // Standing on a node, not merely saying "idle" somewhere along a segment it may still drive.
     boolean stopped=!withdrawal.inFlight()&&vehicle.online()&&vehicle.nodeId()!=null&&!vehicle.betweenNodes()
      &&vehicle.idle()&&"IDLE".equals(vehicle.effectiveStatus())&&vehicle.reportedAt()!=null&&vehicle.reportedAt().isAfter(stopAt)
      &&vehicle.reportedSince(cutoff);
     if(!stopped) return none(vehicleId,decision+"_STOPPING");
    }
    // A STOP that never left must not reach the vehicle later and cut short its next move.
    outbox.withdrawIfUnsent(stopCommand,"MOVE_CLOSED");
    ledger.finishRelocation(relocation.id(),decision);
    traffic.releaseAhead(siteId,vehicleId,vehicle.nodeId(),"MOVE_"+decision);
   }
   return none(vehicleId,decision);
  }
  if(vehicle.nodeId()==null) return none(vehicleId,"POSITION_UNKNOWN");
  if(!vehicle.reportedSince(cutoff)) return none(vehicleId,"POSITION_STALE");
  if(!vehicle.online()||!vehicle.remote()) return none(vehicleId,"VEHICLE_NOT_CONTROLLABLE");
  if(!vehicle.idle()) return none(vehicleId,"VEHICLE_MOVING");
  if(!outbox.unresolvedForVehicle(siteId,vehicleId).isEmpty()) return none(vehicleId,"COMMAND_OUTSTANDING");
  var held=traffic.heldBy(siteId,vehicleId);
  int heldIndex=indexOfNode(held,vehicle.nodeId());
  if(heldIndex>=0&&heldIndex<held.size()-1) return none(vehicleId,"AUTHORITY_NOT_USED");

  var map=topology.getMap(siteId,revisionId);
  if(relocation==null){
   var place=nearestClearPlace(siteId,map,zoneOf.keySet(),vehicle,vehicles);
   if(place==null) return none(vehicleId,"NO_PLACE_OUTSIDE");
   relocation=ledger.startRelocation(siteId,revisionId,vehicleId,zone,place.nodeIds(),place.nodeIds().getLast(),
    Relocation.ZONE_EVACUATION);
  }
  var route=relocation.routeNodeIds();
  int at=route.indexOf(vehicle.nodeId());
  if(at<0){
   // Off the stored path, so the path is planned again from where it actually is.
   var again=planner.find(map,vehicle.nodeId(),relocation.targetNodeId());
   if(again.status()==RouteResult.Status.UNREACHABLE){
    ledger.finishRelocation(relocation.id(),"NO_PATH");
    return none(vehicleId,"NO_PATH");
   }
   route=again.nodeIds(); at=0;
   ledger.replaceRelocationRoute(relocation.id(),route);
  }
  if(at==route.size()-1){
   ledger.finishRelocation(relocation.id(),"ARRIVED");
   return none(vehicleId,"ARRIVED");
  }

  ReservationGrant grant=traffic.request(siteId,revisionId,vehicleId,null,route.subList(at,route.size()));
  if(grant.nodeIds().size()<=1) return none(vehicleId,grant.reason()==null?"NOTHING_TO_ADD":grant.reason());
  var added=new ArrayList<>(grant.nodeIds()); added.remove(vehicle.nodeId());
  String commandId="relocation-"+relocation.id()+"-seq-"+traffic.heldBy(siteId,vehicleId).getLast().sequence();
  outbox.enqueueForVehicle(siteId,vehicleId,commandId,"MOVE",payload(vehicle,relocation,grant,added));
  String moving=switch(relocation.purpose()){
   case Relocation.MANUAL->"MOVING_TO_TARGET"; case Relocation.YIELD->"YIELDING";
   case Relocation.HOME->"GOING_HOME"; case Relocation.CHARGE->"GOING_TO_CHARGE"; default->"EVACUATING";
  };
  return new Step(vehicleId,moving,added,commandId);
 }

 /**
  * An operator sends a vehicle to a node. Refused unless the vehicle is in use, reporting lately,
  * under remote control, idle, holds no order, has no move open and no command unsettled, and the
  * node is on the map, usable and reachable. The first stretch is granted and commanded at once; the
  * rest follows on later sweeps like any move out of a zone.
  */
 @Transactional
 public Step moveTo(long siteId,long vehicleId,long targetNodeId){
  long revisionId=ledger.activeRevision(siteId);
  fleet.lockForAssignment(siteId,List.of(vehicleId));
  var vehicle=fleet.find(siteId,revisionId,vehicleId)
   .orElseThrow(()->new TransportRejected("VEHICLE_NOT_FOUND","unknown vehicle",false));
  var cutoff=OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());
  if(!vehicle.enabled()) throw new TransportRejected("VEHICLE_DISABLED","vehicle is not in use",false);
  if(ledger.assignedVehicleIds(siteId).contains(vehicleId))
   throw new TransportRejected("VEHICLE_HAS_ORDER","vehicle is carrying out an order",true);
  if(ledger.relocatingVehicleIds(siteId).contains(vehicleId))
   throw new TransportRejected("VEHICLE_ALREADY_MOVING","vehicle already has a move under way",true);
  if(vehicle.nodeId()==null) throw new TransportRejected("POSITION_UNKNOWN","nobody has reported where it is",true);
  if(!vehicle.reportedSince(cutoff)) throw new TransportRejected("POSITION_STALE","no recent report from the vehicle",true);
  if(!vehicle.online()||!vehicle.remote()) throw new TransportRejected("VEHICLE_NOT_REMOTE","vehicle is not under remote control",true);
  if(!vehicle.idle()) throw new TransportRejected("VEHICLE_NOT_IDLE","vehicle is not idle",true);
  if(!outbox.unresolvedForVehicle(siteId,vehicleId).isEmpty())
   throw new TransportRejected("COMMAND_OUTSTANDING","an earlier command is not settled yet",true);
  var map=topology.getMap(siteId,revisionId);
  var target=map.nodes().stream().filter(n->n.id()==targetNodeId).findFirst()
   .orElseThrow(()->new TransportRejected("TARGET_NODE_INVALID","node is not on the active map",false));
  if(!target.enabled()) throw new TransportRejected("TARGET_NODE_INVALID","node is out of service",false);
  if(targetNodeId==vehicle.nodeId()) throw new TransportRejected("ALREADY_AT_TARGET","vehicle already stands there",false);
  var route=planner.find(map,vehicle.nodeId(),targetNodeId);
  if(route.status()!=RouteResult.Status.FOUND) throw new TransportRejected("NO_PATH","no route from where it stands",false);
  ledger.startRelocation(siteId,revisionId,vehicleId,null,route.nodeIds(),targetNodeId,Relocation.MANUAL);
  var zoneOf=new HashMap<Long,Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId))
   if(zone.enabled()&&zone.revisionId()==revisionId) for(Long node:zone.nodeIds()) zoneOf.put(node,zone.id());
  return step(siteId,revisionId,vehicleId,zoneOf,fleet.snapshot(siteId,revisionId),cutoff);
 }

 /**
  * An operator stops a vehicle's move to a node. Nothing more is granted or sent; what never left
  * is taken back. A vehicle that may already be driving on what it was given keeps that road until
  * it reports, after this request, that it stands idle; then the move closes. Refused when the
  * vehicle has no such move. A move out of a zone is not stopped here: the next sweep would only
  * start it again; taking the vehicle out of automatic work is how that is stopped.
  */
 @Transactional
 public Step stopMove(long siteId,long vehicleId){
  long revisionId=ledger.activeRevision(siteId);
  fleet.lockForAssignment(siteId,List.of(vehicleId));
  var relocation=ledger.openRelocations(siteId).stream().filter(r->r.vehicleId()==vehicleId).findFirst()
   .orElseThrow(()->new TransportRejected("NO_MOVE","vehicle has no move under way",false));
  if(!Relocation.MANUAL.equals(relocation.purpose()))
   throw new TransportRejected("NOT_A_MANUAL_MOVE","only an operator's move can be stopped here",false);
  ledger.requestRelocationStop(relocation.id());
  var cutoff=OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());
  var zoneOf=new HashMap<Long,Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId))
   if(zone.enabled()&&zone.revisionId()==revisionId) for(Long node:zone.nodeIds()) zoneOf.put(node,zone.id());
  return step(siteId,revisionId,vehicleId,zoneOf,fleet.snapshot(siteId,revisionId),cutoff);
 }

 /** Open moves without an order at the site: who is going where, why, and whether a stop was asked. */
 @Transactional(readOnly=true)
 public List<Relocation> openMoves(long siteId){ return ledger.openRelocations(siteId); }

 /**
  * A vehicle with nothing to do that stands where another one has been waiting to go for longer
  * than the site allows is moved out of the way: to its home node when that lies off the waiting
  * vehicle's road, otherwise to the nearest clear place off that road (the same rules as leaving a
  * zone). Only a vehicle in use and in automatic work, idle on a node, fresh, holding no order, no
  * move and no unsettled command is moved; one move per vehicle and pass.
  */
 @Transactional
 public List<Step> yieldAll(long siteId){
  var settings=operations.get(siteId).settings();
  if(settings.yieldAfterSeconds()<=0) return List.of();
  long revisionId=ledger.activeRevision(siteId);
  var now=OffsetDateTime.now();
  var cutoff=now.minusSeconds(settings.positionStaleSeconds());
  var waitedLongEnough=now.minusSeconds(settings.yieldAfterSeconds());
  var out=new ArrayList<Step>(); var moved=new HashSet<Long>();
  for(var wait:traffic.waitingFor(siteId)){
   if(wait.since().isAfter(waitedLongEnough)||!moved.add(wait.blockerId())) continue;
   out.add(yieldFor(siteId,revisionId,wait,cutoff));
  }
  return out;
 }

 private Step yieldFor(long siteId,long revisionId,com.amhs.traffic.WaitingFor wait,OffsetDateTime cutoff){
  long id=wait.blockerId();
  fleet.lockForAssignment(siteId,List.of(id));
  var vehicle=fleet.find(siteId,revisionId,id).orElse(null);
  String busy=whyNotFree(siteId,vehicle,cutoff);
  if(busy!=null) return none(id,"YIELD_"+busy);
  var map=topology.getMap(siteId,revisionId);
  // Off the waiting vehicle's road: what it holds, what it wants and what it is still to drive.
  var avoid=new HashSet<Long>(zoneNodes(siteId,revisionId));
  avoid.add(wait.wantedNodeId());
  for(NodeHold hold:traffic.heldBy(siteId,wait.vehicleId())) avoid.add(hold.nodeId());
  for(var a:ledger.activeAssignments(siteId))
   if(a.vehicleId()==wait.vehicleId()) avoid.addAll(ledger.plannedRoute(a.orderId()));
  for(var r:ledger.openRelocations(siteId)) if(r.vehicleId()==wait.vehicleId()) avoid.addAll(r.routeNodeIds());
  avoid.remove(vehicle.nodeId());
  var place=nearestClearPlace(siteId,map,avoid,vehicle,fleet.snapshot(siteId,revisionId));
  if(place==null) return none(id,"NO_PLACE_TO_YIELD");
  ledger.startRelocation(siteId,revisionId,id,null,place.nodeIds(),place.nodeIds().getLast(),Relocation.YIELD);
  return step(siteId,revisionId,id,zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff);
 }

 /**
  * A vehicle with a home node, standing elsewhere with nothing to do for longer than the site
  * allows, goes home when its home is free (nobody stands on, holds or is heading for it). A
  * vehicle with a carrier on board stays where it is, as in the existing system.
  */
 @Transactional
 public List<Step> returnHome(long siteId){
  var settings=operations.get(siteId).settings();
  if(settings.homeReturnSeconds()<=0) return List.of();
  long revisionId=ledger.activeRevision(siteId);
  var now=OffsetDateTime.now();
  var cutoff=now.minusSeconds(settings.positionStaleSeconds());
  var quietSince=now.minusSeconds(settings.homeReturnSeconds());
  var out=new ArrayList<Step>();
  var candidates=fleet.snapshot(siteId,revisionId).stream()
   .filter(v->v.homeNodeId()!=null&&v.nodeId()!=null&&!v.homeNodeId().equals(v.nodeId())&&!v.carrierPresent())
   .sorted(Comparator.comparingLong(VehicleSnapshot::id)).toList();
  for(var seen:candidates){
   var ended=ledger.lastActivityEnded(siteId,seen.id());
   if(ended!=null&&ended.isAfter(quietSince)) continue;
   fleet.lockForAssignment(siteId,List.of(seen.id()));
   var vehicle=fleet.find(siteId,revisionId,seen.id()).orElse(null);
   if(whyNotFree(siteId,vehicle,cutoff)!=null||vehicle.carrierPresent()) continue;
   long home=vehicle.homeNodeId();
   if(placeTaken(siteId,revisionId,home,vehicle.id())) continue;
   var route=planner.find(topology.getMap(siteId,revisionId),vehicle.nodeId(),home);
   if(route.status()!=RouteResult.Status.FOUND) continue;
   ledger.startRelocation(siteId,revisionId,vehicle.id(),null,route.nodeIds(),home,Relocation.HOME);
   out.add(step(siteId,revisionId,vehicle.id(),zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff));
  }
  return out;
 }

 /** Null when the vehicle may be sent somewhere on the controller's own initiative; otherwise why not. */
 private String whyNotFree(long siteId,VehicleSnapshot vehicle,OffsetDateTime cutoff){
  if(vehicle==null) return "VEHICLE_NOT_FOUND";
  if(!vehicle.enabled()) return "VEHICLE_DISABLED";
  if(!vehicle.assignmentEnabled()) return "ASSIGNMENT_DISABLED";
  if(vehicle.nodeId()==null) return "POSITION_UNKNOWN";
  if(!vehicle.reportedSince(cutoff)) return "POSITION_STALE";
  if(!vehicle.online()||!vehicle.remote()) return "VEHICLE_NOT_CONTROLLABLE";
  if(!vehicle.idle()||!"IDLE".equals(vehicle.effectiveStatus())) return "VEHICLE_NOT_IDLE";
  if(ledger.assignedVehicleIds(siteId).contains(vehicle.id())) return "HAS_ORDER";
  if(ledger.relocatingVehicleIds(siteId).contains(vehicle.id())) return "ALREADY_MOVING";
  if(!outbox.unresolvedForVehicle(siteId,vehicle.id()).isEmpty()) return "COMMAND_OUTSTANDING";
  return null;
 }
 /** Somebody else stands on, holds, or is being sent to this node. */
 private boolean placeTaken(long siteId,long revisionId,long nodeId,long vehicleId){
  for(var v:fleet.snapshot(siteId,revisionId)) if(v.id()!=vehicleId&&Long.valueOf(nodeId).equals(v.nodeId())) return true;
  for(NodeHold hold:traffic.held(siteId)) if(hold.vehicleId()!=vehicleId&&hold.nodeId()==nodeId) return true;
  for(var r:ledger.openRelocations(siteId)) if(r.vehicleId()!=vehicleId&&r.targetNodeId()==nodeId) return true;
  return false;
 }
 private Set<Long> zoneNodes(long siteId,long revisionId){ return zoneOf(siteId,revisionId).keySet(); }
 private Map<Long,Long> zoneOf(long siteId,long revisionId){
  var zoneOf=new HashMap<Long,Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId))
   if(zone.enabled()&&zone.revisionId()==revisionId) for(Long node:zone.nodeIds()) zoneOf.put(node,zone.id());
  return zoneOf;
 }

 /**
  * Where the vehicle goes. Its home node comes first: somebody chose that place for it. It is used
  * when it lies outside every zone, nobody else stands on or holds it, and it can be reached.
  * Otherwise, the cheapest place outside every zone that does not stand in anybody's way: not a
  * merge point, which every vehicle joining there has to pass, not a port, which work will come to,
  * and not a node another vehicle stands on or holds. Ties go to the lower node id so the choice
  * repeats.
  */
 private RouteResult nearestClearPlace(long siteId,MapSnapshot map,Set<Long> zoneNodes,VehicleSnapshot vehicle,
                                       List<VehicleSnapshot> vehicles){
  var ports=new HashSet<Long>();
  for(var port:map.ports()) ports.add(port.linkedNodeId());
  var taken=new HashSet<Long>();
  for(var other:vehicles) if(other.id()!=vehicle.id()&&other.nodeId()!=null) taken.add(other.nodeId());
  for(NodeHold hold:traffic.held(siteId)) if(hold.vehicleId()!=vehicle.id()) taken.add(hold.nodeId());
  Long home=vehicle.homeNodeId();
  if(home!=null&&!zoneNodes.contains(home)&&!taken.contains(home)){
   var toHome=planner.find(map,vehicle.nodeId(),home);
   if(toHome.status()==RouteResult.Status.FOUND) return toHome;
  }
  RouteResult best=null;
  for(var node:map.nodes()){
   if(!node.enabled()||node.mergePoint()||zoneNodes.contains(node.id())||ports.contains(node.id())||taken.contains(node.id()))
    continue;
   var route=planner.find(map,vehicle.nodeId(),node.id());
   if(route.status()!=RouteResult.Status.FOUND) continue;
   if(best==null||route.costSeconds()<best.costSeconds()
     ||(route.costSeconds()==best.costSeconds()&&node.id()<best.nodeIds().getLast()))
    best=route;
  }
  return best;
 }

 private static int indexOfNode(List<NodeHold> holds,long nodeId){
  for(int i=0;i<holds.size();i++) if(holds.get(i).nodeId()==nodeId) return i;
  return -1;
 }
 private static Step none(long vehicleId,String decision){ return new Step(vehicleId,decision,List.of(),null); }
 private String payload(VehicleSnapshot vehicle,Relocation relocation,ReservationGrant grant,List<Long> added){
  var body=new LinkedHashMap<String,Object>();
  body.put("vehicleId",vehicle.id()); body.put("vehicleCode",vehicle.code());
  body.put("purpose",Relocation.MANUAL.equals(relocation.purpose())?"MANUAL_MOVE":relocation.purpose());
  body.put("fromNodeId",vehicle.nodeId());
  body.put("targetNodeId",relocation.targetNodeId());
  body.put("authorizedNodeIds",grant.nodeIds());
  body.put("addedNodeIds",added);
  body.put("authorityComplete",grant.complete());
  try{ return json.writeValueAsString(body); }
  catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }
}
