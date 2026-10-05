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
  var zoneOf=zoneOf(siteId,revisionId);
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
  // Taken out of use by somebody: it is not moved on the controller's own initiative either. It
  // stays where it is until an operator moves it or turns it back on.
  //
  // Not for dispatch does not stop a move. That setting excludes the vehicle from transport work,
  // and a vehicle still on the track has to charge, give way and go home whether or not it is
  // being given jobs -- stopping those is how a vehicle nobody dispatches ends up flat in front of
  // everything else.
  String decision=!vehicle.enabled()?"VEHICLE_DISABLED"
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
  // Driving on an open move: lengthen its authority the way an order's is (MORE), so it does not
  // stop at the end of every grant.
  if(relocation!=null&&"MOVING".equals(vehicle.effectiveStatus())) return extendMove(siteId,revisionId,vehicle,relocation);
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
  requireRunning(siteId);
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
  return step(siteId,revisionId,vehicleId,zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff);
 }

 /**
  * An operator sends a vehicle to charge now, or tells it to stop charging.
  *
  * <p>The thresholds are what the controller decides on its own. This is somebody deciding for
  * their own reason -- a shift ending, a vehicle they want full before a busy hour -- so the level
  * is not consulted. What is still consulted is everything that makes charging possible at all: a
  * vehicle in use, controllable, standing idle with nothing in flight, carrying nothing, and a
  * charge point free to take it.
  *
  * <p>Starting puts the vehicle under the ordinary charge request, so the same rules end it: it
  * stops when charged past the release level, or when work of its kind is waiting and nothing else
  * is free. Stopping clears the request, but a charge below the site's critical level is the
  * controller's to insist on and will be asked for again.
  */
 @Transactional
 public Step chargeNow(long siteId,long vehicleId,boolean start){
  requireRunning(siteId);
  long revisionId=ledger.activeRevision(siteId);
  fleet.lockForAssignment(siteId,List.of(vehicleId));
  var vehicle=fleet.find(siteId,revisionId,vehicleId)
   .orElseThrow(()->new TransportRejected("VEHICLE_NOT_FOUND","unknown vehicle",false));
  if(vehicle.chargingPolicy()==null||"DISABLED".equals(vehicle.chargingPolicy()))
   throw new TransportRejected("CHARGING_DISABLED","this vehicle has no charge of its own",false);
  boolean charging="CHARGING".equals(vehicle.effectiveStatus());
  if(!outbox.unresolvedForVehicle(siteId,vehicleId).isEmpty())
   throw new TransportRejected("COMMAND_OUTSTANDING","an earlier command is not settled yet",true);
  if(!start){
   if(!charging) throw new TransportRejected("NOT_CHARGING","the vehicle is not charging",true);
   ledger.setChargeLevel(siteId,vehicleId,0);
   if(ledger.chargeSession(siteId,vehicleId)==null) ledger.openChargeSession(siteId,vehicleId);
   ledger.requestChargeStop(siteId,vehicleId);
   chargeCommand(siteId,vehicle,"CHARGE_STOP","OPERATOR");
   return none(vehicleId,"CHARGE_STOPPING");
  }
  if(charging) throw new TransportRejected("ALREADY_CHARGING","the vehicle is already charging",true);
  if(vehicle.carrierPresent())
   throw new TransportRejected("CARRIER_ON_BOARD","the vehicle is holding a carrier",true);
  var cutoff=OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());
  String busy=whyNotFree(siteId,vehicle,cutoff);
  if(busy!=null) throw new TransportRejected(busy,"the vehicle cannot be sent to charge now",true);
  var charger=chooseCharger(siteId,revisionId,topology.getMap(siteId,revisionId),vehicle);
  if(charger==null) throw new TransportRejected("NO_FREE_CHARGER","no charge point it can reach is free",true);
  ledger.setChargeLevel(siteId,vehicleId,1);
  if(charger.route().size()<=1){
   ledger.openChargeSession(siteId,vehicleId);
   chargeCommand(siteId,vehicle,"CHARGE_START","OPERATOR");
   return none(vehicleId,"CHARGE_STARTED");
  }
  ledger.startRelocation(siteId,revisionId,vehicleId,null,charger.route(),charger.nodeId(),Relocation.CHARGE);
  return step(siteId,revisionId,vehicleId,zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff);
 }

 /**
  * MORE for a move without an order, by the same rules as an order's: when it is enabled, the
  * vehicle drives on what it holds, no command of it is unsettled, the vehicle accepts a changed
  * destination where the site waits for that, the remaining authority has reached the threshold
  * and is not below the floor, and it does not end at a stop node on purpose.
  */
 private Step extendMove(long siteId,long revisionId,VehicleSnapshot vehicle,Relocation relocation){
  long vehicleId=vehicle.id();
  var options=operations.get(siteId).settings();
  if(!options.moreEnabled()) return none(vehicleId,"MORE_DISABLED");
  if(!outbox.unresolvedForVehicle(siteId,vehicleId).isEmpty()) return none(vehicleId,"MORE_OUTSTANDING");
  if(options.moreVehicleSignal()&&!Boolean.TRUE.equals(vehicle.destinationChangeEnabled()))
   return none(vehicleId,"VEHICLE_NOT_ACCEPTING_CHANGE");
  var held=traffic.heldBy(siteId,vehicleId);
  int heldIndex=indexOfNode(held,vehicle.nodeId());
  if(heldIndex<0) return none(vehicleId,"POSITION_OUTSIDE_AUTHORITY");
  var route=relocation.routeNodeIds();
  int at=route.indexOf(vehicle.nodeId());
  if(at<0) return none(vehicleId,"POSITION_OFF_ROUTE");
  var remaining=route.subList(at,route.size());
  var heldFromHere=held.subList(heldIndex,held.size()).stream().map(NodeHold::nodeId).toList();
  if(heldFromHere.size()>=remaining.size()) return none(vehicleId,"ROUTE_COMPLETE");
  var map=topology.getMap(siteId,revisionId);
  if(heldFromHere.size()>1&&ContinuousMovement.isStopNode(map,heldFromHere.getLast())) return none(vehicleId,"STOP_NODE_AHEAD");
  if(heldFromHere.size()-1<options.moreMinRemainingNodes()) return none(vehicleId,"BELOW_MORE_FLOOR");
  if(!ContinuousMovement.thresholdReached(map,options,heldFromHere,remaining)) return none(vehicleId,"NOT_NEEDED");
  var grant=traffic.request(siteId,revisionId,vehicleId,null,remaining);
  var added=new ArrayList<>(grant.nodeIds()); added.removeAll(heldFromHere);
  if(added.isEmpty()) return none(vehicleId,grant.reason()==null?"NOTHING_TO_ADD":grant.reason());
  String commandId="relocation-"+relocation.id()+"-more-seq-"+traffic.heldBy(siteId,vehicleId).getLast().sequence();
  outbox.enqueueForVehicle(siteId,vehicleId,commandId,"MORE",payload(vehicle,relocation,grant,added));
  return new Step(vehicleId,"EXTENDED",added,commandId);
 }

 /** Where each vehicle with an open move is going and the nodes still ahead of it on that move. */
 public record Heading(long vehicleId,String purpose,long targetNodeId,List<Long> remainingNodeIds){}
 @Transactional(readOnly=true)
 public List<Heading> headings(long siteId){
  long revisionId=ledger.activeRevision(siteId);
  var where=new HashMap<Long,Long>();
  for(var v:fleet.snapshot(siteId,revisionId)) if(v.nodeId()!=null) where.put(v.id(),v.nodeId());
  var out=new ArrayList<Heading>();
  for(var a:ledger.activeAssignments(siteId)){
   var route=ledger.plannedRoute(a.orderId());
   if(route.isEmpty()) continue;
   int from=Math.max(0,Math.min(ledger.routeCursor(a.orderId()),route.size()-1));
   Long at=where.get(a.vehicleId());
   // The cursor follows the vehicle; if it lags, start where the vehicle actually is.
   if(at!=null){ int i=route.subList(from,route.size()).indexOf(at); if(i>=0) from+=i; }
   out.add(new Heading(a.vehicleId(),"ORDER",route.getLast(),List.copyOf(route.subList(from,route.size()))));
  }
  for(var r:ledger.openRelocations(siteId)){
   var route=r.routeNodeIds();
   Long at=where.get(r.vehicleId());
   int from=at==null?0:Math.max(0,route.indexOf(at));
   out.add(new Heading(r.vehicleId(),r.purpose(),r.targetNodeId(),List.copyOf(route.subList(from,route.size()))));
  }
  return out;
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
  return step(siteId,revisionId,vehicleId,zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff);
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
  * A vehicle standing with nothing to do for longer than the site allows goes to its waiting
  * place, when that place is free (nobody stands on, holds or is heading for it). A vehicle with a
  * carrier on board stays where it is, as in the existing system.
  *
  * <p>Its own home node is its waiting place when it has one. Otherwise the site's waiting places
  * for its kind are used, nearest first -- that is what lets a site be laid out once instead of
  * every vehicle being given its own spot, and it is why the places name a kind: the two kinds do
  * not share track.
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
  var map=topology.getMap(siteId,revisionId);
  var candidates=fleet.snapshot(siteId,revisionId).stream()
   .filter(v->v.nodeId()!=null&&!v.carrierPresent()&&!waitingPlaces(map,v).isEmpty())
   .sorted(Comparator.comparingLong(VehicleSnapshot::id)).toList();
  for(var seen:candidates){
   // Idle long enough: since it last became idle on a node, and since its last work or move ended.
   if(seen.idleSince()==null||seen.idleSince().isAfter(quietSince)) continue;
   var ended=ledger.lastActivityEnded(siteId,seen.id());
   if(ended!=null&&ended.isAfter(quietSince)) continue;
   fleet.lockForAssignment(siteId,List.of(seen.id()));
   var vehicle=fleet.find(siteId,revisionId,seen.id()).orElse(null);
   if(whyNotFree(siteId,vehicle,cutoff)!=null||vehicle.carrierPresent()) continue;
   // Already standing in one of them: it is where it is meant to be.
   var places=waitingPlaces(map,vehicle);
   if(places.contains(vehicle.nodeId())) continue;
   RouteResult best=null; Long target=null;
   for(long place:places){
    if(placeTaken(siteId,revisionId,place,vehicle.id())) continue;
    var route=planner.find(map,vehicle.nodeId(),place);
    if(route.status()!=RouteResult.Status.FOUND) continue;
    if(best==null||route.costSeconds()<best.costSeconds()){ best=route; target=place; }
   }
   if(best==null) continue;
   ledger.startRelocation(siteId,revisionId,vehicle.id(),null,best.nodeIds(),target,Relocation.HOME);
   out.add(step(siteId,revisionId,vehicle.id(),zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff));
  }
  return out;
 }

 /**
  * AGV charging, as the existing system decides it. Each AGV that charges (policy HOME or NEAREST)
  * and reports a state of charge keeps a request level: at or below the request threshold it asks to
  * charge (1), unless it is working; below the critical threshold without a carrier it must charge
  * (2), which also keeps it out of dispatch; above the release threshold the request ends. Work
  * clears an ordinary request.
  *
  * <p>A free AGV with a request is sent to its charge node (HOME) or the nearest free charge point
  * (NEAREST); on arrival it is told to start charging. A charge point nobody else stands on, holds
  * or is heading for is taken under a lock on it, so two vehicles never pick the same one. A
  * charging AGV is told to stop once charged past the release threshold, or when work of its kind
  * is waiting and no other vehicle of its kind is free and it does not have to charge.
  * {@code freeByKind} is what dispatch left free in this pass.
  */
 @Transactional
 public List<Step> chargeAll(long siteId,Map<com.amhs.platform.VehicleType,Integer> freeByKind){
  var settings=operations.get(siteId).settings();
  long revisionId=ledger.activeRevision(siteId);
  var cutoff=OffsetDateTime.now().minusSeconds(settings.positionStaleSeconds());
  var levels=ledger.chargeLevels(siteId);
  var out=new ArrayList<Step>();
  var candidates=fleet.snapshot(siteId,revisionId).stream()
   .filter(v->v.vType()==com.amhs.platform.VehicleType.AGV&&v.batteryPct()!=null
    &&v.chargingPolicy()!=null&&!"DISABLED".equals(v.chargingPolicy()))
   .sorted(Comparator.comparingLong(VehicleSnapshot::id)).toList();
  for(var seen:candidates){
   fleet.lockForAssignment(siteId,List.of(seen.id()));
   var v=fleet.find(siteId,revisionId,seen.id()).orElse(null);
   if(v==null||v.batteryPct()==null) continue;
   double b=v.batteryPct();
   boolean working=ledger.assignedVehicleIds(siteId).contains(v.id());
   int level=levels.getOrDefault(v.id(),0), next=level;
   if(level==0){
    if(b<=settings.chargeRequestPct()){
     if(b<settings.chargeCriticalPct()&&!v.carrierPresent()) next=2; else if(!working) next=1;
    }
   }else if(level==1){
    if(working) next=0;
    else if(b<settings.chargeCriticalPct()&&!v.carrierPresent()) next=2;
    else if(b>settings.chargeReleasePct()) next=0;
   }else if(b>settings.chargeReleasePct()) next=0;
   if(next!=level) ledger.setChargeLevel(siteId,v.id(),next);

   boolean idleCommands=outbox.unresolvedForVehicle(siteId,v.id()).isEmpty();
   boolean charging="CHARGING".equals(v.effectiveStatus());
   var session=ledger.chargeSession(siteId,v.id());
   // A charge owns the vehicle from being told to start until it is seen to have stopped.
   if(session!=null&&!charging){
    var stop=session.stopRequestedAt();
    if(stop!=null&&idleCommands&&v.reportedAt()!=null&&v.reportedAt().isAfter(stop)){
     ledger.closeChargeSession(siteId,v.id());
     out.add(none(v.id(),"CHARGE_ENDED"));
    }else if(stop==null&&idleCommands&&v.reportedAt()!=null
      &&v.reportedAt().isAfter(session.startedAt().plusSeconds(10))){
     // Told to start, the command settled, and well afterwards the vehicle still is not charging.
     ledger.closeChargeSession(siteId,v.id());
     out.add(none(v.id(),"CHARGE_NOT_STARTED"));
    }else out.add(none(v.id(),stop==null?"CHARGE_STARTING":"CHARGE_STOPPING"));
    continue;
   }
   if(charging){
    boolean full=b>settings.chargeReleasePct();
    boolean workWaits=next<2&&ledger.queuedCount(siteId,v.vType())>0&&freeByKind.getOrDefault(v.vType(),0)<=0;
    if((full||workWaits||next==0)&&idleCommands&&(session==null||session.stopRequestedAt()==null)){
     if(workWaits&&next!=0) ledger.setChargeLevel(siteId,v.id(),0);
     if(session==null) ledger.openChargeSession(siteId,v.id());
     ledger.requestChargeStop(siteId,v.id());
     chargeCommand(siteId,v,"CHARGE_STOP",full?"CHARGED":"WORK_WAITING");
     out.add(none(v.id(),full||next==0?"CHARGE_COMPLETE":"CHARGE_STOPPED_FOR_WORK"));
    }
    continue;
   }
   if(next==0) continue;
   if(v.carrierPresent()){ out.add(none(v.id(),"CHARGE_WAITS_FOR_CARRIER")); continue; }
   // Why a vehicle that is asking to charge is not being sent. Silence here is the state nobody
   // can explain: a battery falling on the screen with no reason given anywhere.
   String busy=whyNotFree(siteId,v,cutoff);
   if(busy!=null){ out.add(none(v.id(),"CHARGE_"+busy)); continue; }
   var map=topology.getMap(siteId,revisionId);
   var charger=chooseCharger(siteId,revisionId,map,v);
   if(charger==null){ out.add(none(v.id(),"NO_FREE_CHARGER")); continue; }
   if(charger.route().size()<=1){
    ledger.openChargeSession(siteId,v.id());
    chargeCommand(siteId,v,"CHARGE_START",next==2?"MUST_CHARGE":"CHARGE_REQUEST");
    out.add(none(v.id(),"CHARGE_STARTED"));
    continue;
   }
   ledger.startRelocation(siteId,revisionId,v.id(),null,charger.route(),charger.nodeId(),Relocation.CHARGE);
   out.add(step(siteId,revisionId,v.id(),zoneOf(siteId,revisionId),fleet.snapshot(siteId,revisionId),cutoff));
  }
  return out;
 }

 /**
  * The charge point this vehicle should use and the route there, or null when none is free and
  * reachable. A one-node route means it already stands there. HOME uses the vehicle's own charge node only;
  * NEAREST also considers every charge point on the map and takes the cheapest.
  */
 private record Charger(long nodeId,List<Long> route,double cost){}
 private Charger chooseCharger(long siteId,long revisionId,MapSnapshot map,VehicleSnapshot v){
  var places=new TreeSet<Long>();
  if(v.chargeNodeId()!=null) places.add(v.chargeNodeId());
  if("NEAREST".equals(v.chargingPolicy()))
   for(var node:map.nodes()) if(node.chargePoint()&&node.enabled()) places.add(node.id());
  Charger best=null;
  for(long place:places){
   var node=map.nodes().stream().filter(n->n.id()==place).findFirst().orElse(null);
   if(node==null||!node.enabled()) continue;
   Charger option;
   if(v.nodeId()!=null&&place==v.nodeId()) option=new Charger(place,List.of(place),0);
   else{
    var route=planner.find(map,v.nodeId(),place);
    if(route.status()!=RouteResult.Status.FOUND) continue;
    option=new Charger(place,route.nodeIds(),route.costSeconds()==null?0:route.costSeconds());
   }
   if(best!=null&&best.cost()<=option.cost()) continue;
   ledger.lockPlace(place);
   if(placeTaken(siteId,revisionId,place,v.id())) continue;
   best=option;
  }
  return best;
 }

 private void chargeCommand(long siteId,VehicleSnapshot v,String type,String reason){
  var body=new LinkedHashMap<String,Object>();
  body.put("vehicleId",v.id()); body.put("vehicleCode",v.code()); body.put("nodeId",v.nodeId());
  body.put("batteryPct",v.batteryPct()); body.put("reason",reason);
  try{
   outbox.enqueueForVehicle(siteId,v.id(),"charge-"+v.id()+"-"+type+"-"+java.util.UUID.randomUUID(),type,json.writeValueAsString(body));
  }catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }

 /**
  * A stopped site sends nothing, including what a person asks for by hand.
  *
  * <p>The alternative is worse than refusing: the command would be written, sit in the outbox
  * because nothing is being published, and look to the person like a vehicle that is about to move.
  */
 private void requireRunning(long siteId){
  if(!operations.sequenceRunning(siteId))
   throw new TransportRejected("SEQUENCE_STOPPED","the site is stopped",true);
 }

 /** Null when the vehicle may be sent somewhere on the controller's own initiative; otherwise why not. */
 private String whyNotFree(long siteId,VehicleSnapshot vehicle,OffsetDateTime cutoff){
  if(vehicle==null) return "VEHICLE_NOT_FOUND";
  if(!vehicle.enabled()) return "VEHICLE_DISABLED";
  // Not for dispatch is deliberately not here. It says this vehicle is not given transport work;
  // it does not say the vehicle may run flat, block a junction or sit in a zone. Charging, giving
  // way and going home are the controller looking after a vehicle that is still on the track.
  if(vehicle.nodeId()==null) return "POSITION_UNKNOWN";
  if(!vehicle.reportedSince(cutoff)) return "POSITION_STALE";
  if(!vehicle.online()||!vehicle.remote()) return "VEHICLE_NOT_CONTROLLABLE";
  if(!vehicle.idle()||!"IDLE".equals(vehicle.effectiveStatus())) return "VEHICLE_NOT_IDLE";
  if(ledger.assignedVehicleIds(siteId).contains(vehicle.id())) return "HAS_ORDER";
  if(ledger.relocatingVehicleIds(siteId).contains(vehicle.id())) return "ALREADY_MOVING";
  // Told to charge and not yet seen to stop: the charge owns it even while it still reports idle.
  if(ledger.chargingVehicleIds(siteId).contains(vehicle.id())) return "CHARGING";
  if(!outbox.unresolvedForVehicle(siteId,vehicle.id()).isEmpty()) return "COMMAND_OUTSTANDING";
  return null;
 }
 /**
  * Where this vehicle may stand with nothing to do.
  *
  * <p>Its own home node when it has one: that is this vehicle's place and nothing else should take
  * it. Otherwise every node the site marks as a waiting place for its kind -- laid out once for
  * the fleet rather than vehicle by vehicle. A node out of service is not one of them.
  */
 private Set<Long> waitingPlaces(MapSnapshot map,VehicleSnapshot vehicle){
  if(vehicle==null) return Set.of();
  if(vehicle.homeNodeId()!=null){
   var home=map.nodes().stream().filter(n->n.id()==vehicle.homeNodeId()).findFirst().orElse(null);
   return home!=null&&home.enabled()?Set.of(home.id()):Set.of();
  }
  var places=new TreeSet<Long>();
  for(var node:map.nodes()) if(node.enabled()&&node.waitsFor(vehicle.vType())) places.add(node.id());
  return places;
 }

 /** Somebody else stands on, holds, or is being sent to this node. */
 private boolean placeTaken(long siteId,long revisionId,long nodeId,long vehicleId){
  for(var v:fleet.snapshot(siteId,revisionId)) if(v.id()!=vehicleId&&Long.valueOf(nodeId).equals(v.nodeId())) return true;
  for(NodeHold hold:traffic.held(siteId)) if(hold.vehicleId()!=vehicleId&&hold.nodeId()==nodeId) return true;
  for(var r:ledger.openRelocations(siteId)) if(r.vehicleId()!=vehicleId&&r.targetNodeId()==nodeId) return true;
  return false;
 }
 private Set<Long> zoneNodes(long siteId,long revisionId){ return zoneOf(siteId,revisionId).keySet(); }
 /**
  * Which nodes count as being in a zone a vehicle is moved out of.
  *
  * <p>A maintenance area is deliberately not among them. It is the same shape as a traffic zone and
  * the opposite intent: a vehicle is put there because it should not be on the line, and moving it
  * out once it stands idle would undo the only thing anyone wanted. It leaves when somebody drives
  * it out.
  */
 private Map<Long,Long> zoneOf(long siteId,long revisionId){
  var zoneOf=new HashMap<Long,Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId))
   if(zone.enabled()&&zone.revisionId()==revisionId&&!zone.forMaintenance())
    for(Long node:zone.nodeIds()) zoneOf.put(node,zone.id());
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
