package com.amhs.transport;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.platform.OperationOptions;
import com.amhs.topology.MapSnapshot;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.NodeHold;
import com.amhs.traffic.TrafficControl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Keeps assigned vehicles moving along their routes.
 *
 * <p>{@link #extend} lengthens the authority of a vehicle that is already moving, so it runs on
 * instead of stopping at the end of what it was granted (MORE). {@link #resume} gives a vehicle that
 * has come to a stop at the end of its authority the next run (a fresh MOVE): after a stop node,
 * after congestion cleared, or after a lap round a full zone.
 *
 * <p>The authority and the command that carries it are written together. Nothing records that a
 * command was issued before it exists in the outbox, so a send that fails is retried by the outbox
 * rather than leaving a record claiming the vehicle was already told.
 *
 * <p>A second command is never produced while one is still unacknowledged. That, rather than a fixed
 * wait between sends, is the condition worth checking: what must not happen is two overlapping
 * movements, and time only ever approximated that.
 *
 * <p>Neither gives road to an order that is not in a driving stage (loading, unloading, a requested
 * cancel, a recovery), nor acts on a vehicle state nobody has confirmed lately, nor reaches past the
 * pickup before the pickup has been reported.
 */
@Service
public class ContinuousMovement {
 private final TransportLedger ledger; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox; private final TopologyQuery topology;
 private final OperationOptionConfiguration operations; private final ObjectMapper json;
 private final ZoneDetour detour;
 public ContinuousMovement(TransportLedger ledger,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,
                           TopologyQuery topology,OperationOptionConfiguration operations,ObjectMapper json,
                           ZoneDetour detour){
  this.ledger=ledger; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox;
  this.topology=topology; this.operations=operations; this.json=json; this.detour=detour;
 }

 /** {@code decision} says why nothing was extended when {@code addedNodeIds} is empty. */
 public record Extension(long orderId,long vehicleId,String decision,List<Long> addedNodeIds,String commandId){
  public Extension { addedNodeIds=List.copyOf(addedNodeIds); }
 }

 @Transactional
 public Extension extend(long siteId,long orderId){
  var order=ledger.lock(siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  if(order.status().isTerminal()) return none(orderId,0,"ORDER_FINISHED");
  var assignment=ledger.activeAssignment(siteId,orderId);
  if(assignment.isEmpty()) return none(orderId,0,"NO_ASSIGNMENT");
  long vehicleId=assignment.get().vehicleId();
  if(!order.status().movable()) return none(orderId,vehicleId,"ORDER_NOT_MOVABLE");

  OperationOptions options=operations.get(siteId).settings();
  if(!options.moreEnabled()) return none(orderId,vehicleId,"MORE_DISABLED");

  var vehicle=fleet.find(siteId,order.revisionId(),vehicleId);
  if(vehicle.isEmpty()) return none(orderId,vehicleId,"VEHICLE_NOT_FOUND");
  var state=vehicle.get();
  if(state.nodeId()==null) return none(orderId,vehicleId,"POSITION_UNKNOWN");
  if(!state.reportedSince(staleBefore(options))) return none(orderId,vehicleId,"POSITION_STALE");
  if(!state.online()||!state.remote()) return none(orderId,vehicleId,"VEHICLE_NOT_CONTROLLABLE");
  // MORE lengthens a movement in progress. A stopped vehicle needs a movement command, not an
  // extension of one it is no longer running; resume() gives it that.
  // Only a vehicle that says it is driving can take an extension. Stopped, faulted, paused,
  // charging, transferring or resetting are all "not moving", whatever the idle flag would suggest.
  String status=state.effectiveStatus();
  if(!"MOVING".equals(status))
   return none(orderId,vehicleId,"IDLE".equals(status)?"VEHICLE_NOT_MOVING":"VEHICLE_STATUS_"+status);

  if(!outbox.unresolved(siteId,orderId,"MORE").isEmpty()) return none(orderId,vehicleId,"MORE_OUTSTANDING");
  // Some vehicles say whether they can take a changed destination right now. When the site waits
  // for that, silence counts as no: a renewal the vehicle cannot act on is a renewal it ignores.
  if(options.moreVehicleSignal()&&!Boolean.TRUE.equals(state.destinationChangeEnabled()))
   return none(orderId,vehicleId,"VEHICLE_NOT_ACCEPTING_CHANGE");

  var held=traffic.heldBy(siteId,vehicleId);
  int heldIndex=indexOfNode(held,state.nodeId());
  if(heldIndex<0) return none(orderId,vehicleId,"POSITION_OUTSIDE_AUTHORITY");

  var route=ledger.plannedRoute(orderId);
  int last=MovementPhase.lastIndex(order.status(),ledger.pickupIndex(orderId),route.size());
  int routeIndex=locate(orderId,route,state.nodeId());
  if(routeIndex<0) return none(orderId,vehicleId,"POSITION_OFF_ROUTE");
  var remainingRoute=route.subList(routeIndex,Math.max(routeIndex,last)+1);
  var heldFromHere=held.subList(heldIndex,held.size()).stream().map(NodeHold::nodeId).toList();
  if(heldFromHere.size()>=remainingRoute.size()) return none(orderId,vehicleId,endOfStage(last,route));

  var map=topology.getMap(siteId,order.revisionId());
  // The authority ends at a stop node on purpose. The vehicle halts there first and is given the
  // next run by resume() once it has, so no MORE carries it through.
  if(heldFromHere.size()>1&&isStopNode(map,heldFromHere.getLast()))
   return none(orderId,vehicleId,"STOP_NODE_AHEAD");
  // Too close to the end of the authority: the vehicle is already braking for it, and a renewal
  // arriving now would not be acted on. It stops, and resume() starts it again.
  if(heldFromHere.size()-1<options.moreMinRemainingNodes())
   return none(orderId,vehicleId,"BELOW_MORE_FLOOR");
  if(!thresholdReached(map,options,heldFromHere,remainingRoute))
   return none(orderId,vehicleId,"NOT_NEEDED");

  var grant=traffic.request(siteId,order.revisionId(),vehicleId,orderId,remainingRoute);
  grant=detour.aroundFullZone(siteId,order.revisionId(),vehicleId,orderId,order.status(),routeIndex,grant);
  var added=new ArrayList<>(grant.nodeIds());
  added.removeAll(heldFromHere);
  // Asking and getting nothing new is congestion, not an error: the vehicle keeps the authority
  // it has and stops at its end if nothing frees up before it gets there.
  if(added.isEmpty()) return none(orderId,vehicleId,grant.reason()==null?"NOTHING_TO_ADD":grant.reason());

  var extended=traffic.heldBy(siteId,vehicleId);
  String commandId="order-"+orderId+"-more-seq-"+extended.getLast().sequence();
  outbox.enqueue(orderId,vehicleId,commandId,"MORE",payload(state,grant.nodeIds(),added,grant.complete()));
  return new Extension(orderId,vehicleId,"EXTENDED",added,commandId);
 }

 /**
  * Starts a stopped vehicle on the next run of its route.
  *
  * <p>Only a vehicle that has used up its authority is resumed: one standing on the last node it
  * was given. A vehicle that still holds nodes ahead has been told to go there and simply has not
  * started yet; a second MOVE would only duplicate the first.
  */
 @Transactional
 public Extension resume(long siteId,long orderId){
  var order=ledger.lock(siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  if(order.status().isTerminal()) return none(orderId,0,"ORDER_FINISHED");
  var assignment=ledger.activeAssignment(siteId,orderId);
  if(assignment.isEmpty()) return none(orderId,0,"NO_ASSIGNMENT");
  long vehicleId=assignment.get().vehicleId();
  if(!order.status().movable()) return none(orderId,vehicleId,"ORDER_NOT_MOVABLE");

  var vehicle=fleet.find(siteId,order.revisionId(),vehicleId);
  if(vehicle.isEmpty()) return none(orderId,vehicleId,"VEHICLE_NOT_FOUND");
  var state=vehicle.get();
  if(state.nodeId()==null) return none(orderId,vehicleId,"POSITION_UNKNOWN");
  if(!state.reportedSince(staleBefore(operations.get(siteId).settings())))
   return none(orderId,vehicleId,"POSITION_STALE");
  if(!state.online()||!state.remote()) return none(orderId,vehicleId,"VEHICLE_NOT_CONTROLLABLE");
  if(!state.idle()) return none(orderId,vehicleId,"VEHICLE_MOVING");
  if(!outbox.unresolved(siteId,orderId,"MOVE").isEmpty()||!outbox.unresolved(siteId,orderId,"MORE").isEmpty())
   return none(orderId,vehicleId,"COMMAND_OUTSTANDING");

  var held=traffic.heldBy(siteId,vehicleId);
  int heldIndex=indexOfNode(held,state.nodeId());
  if(heldIndex>=0&&heldIndex<held.size()-1) return none(orderId,vehicleId,"AUTHORITY_NOT_USED");

  var route=ledger.plannedRoute(orderId);
  int last=MovementPhase.lastIndex(order.status(),ledger.pickupIndex(orderId),route.size());
  int routeIndex=locate(orderId,route,state.nodeId());
  if(routeIndex<0) return none(orderId,vehicleId,"POSITION_OFF_ROUTE");
  var remainingRoute=route.subList(routeIndex,Math.max(routeIndex,last)+1);
  if(remainingRoute.size()<=1) return none(orderId,vehicleId,last<route.size()-1?"AT_PICKUP":"AT_ROUTE_END");

  var grant=traffic.request(siteId,order.revisionId(),vehicleId,orderId,remainingRoute);
  grant=detour.aroundFullZone(siteId,order.revisionId(),vehicleId,orderId,order.status(),routeIndex,grant);
  // Getting only the node it already stands on is no movement at all; the recorded wait says why.
  if(grant.nodeIds().size()<=1) return none(orderId,vehicleId,grant.reason()==null?"NOTHING_TO_ADD":grant.reason());

  var added=new ArrayList<>(grant.nodeIds()); added.remove(state.nodeId());
  var holds=traffic.heldBy(siteId,vehicleId);
  String commandId="order-"+orderId+"-resume-seq-"+holds.getLast().sequence();
  outbox.enqueue(orderId,vehicleId,commandId,"MOVE",payload(state,grant.nodeIds(),added,grant.complete()));
  return new Extension(orderId,vehicleId,"RESUMED",added,commandId);
 }

 /** One pass over everything currently assigned at the site. */
 @Transactional
 public List<Extension> extendAll(long siteId){
  var out=new ArrayList<Extension>();
  for(long orderId:ledger.assignedOrderIds(siteId)) out.add(extend(siteId,orderId));
  return out;
 }

 /** One pass of resume over everything currently assigned at the site. */
 @Transactional
 public List<Extension> resumeAll(long siteId){
  var out=new ArrayList<Extension>();
  for(long orderId:ledger.assignedOrderIds(siteId)) out.add(resume(siteId,orderId));
  return out;
 }

 /**
  * Finds the vehicle in its route from the stored cursor forward and advances the cursor. A route
  * that laps a full zone passes the branch twice, and only the cursor knows which pass this is.
  */
 private int locate(long orderId,List<Long> route,long nodeId){
  int cursor=Math.max(0,ledger.routeCursor(orderId));
  for(int i=cursor;i<route.size();i++){
   if(route.get(i)==nodeId){
    if(i>cursor) ledger.moveRouteCursor(orderId,i);
    return i;
   }
  }
  return -1;
 }

 /**
  * NODE counts how many authorized nodes are still in front of the vehicle.
  *
  * <p>DISTANCE compares how far the vehicle may still travel against a full authority along the
  * route ahead. The run as originally granted is not a usable denominator here: nodes are given
  * back as the vehicle passes them, so by the time the question is asked the original length is
  * gone. A full authority window is stable, and the question it answers is the same one — how
  * little is left compared with a whole grant. Distance is measured between node coordinates
  * because this model stores no separate segment length; a real one would replace this.
  */
 static boolean thresholdReached(MapSnapshot map,OperationOptions options,
                                         List<Long> heldFromHere,List<Long> remainingRoute){
  if("NODE".equals(options.moreType())) return heldFromHere.size()-1<=options.moreRemainingNodes();
  var window=remainingRoute.subList(0,Math.min(remainingRoute.size(),options.nodeCount()));
  double full=length(map,window);
  if(full<=0) return true;
  return length(map,heldFromHere)*100<=(double)options.moreRemainingPercent()*full;
 }
 /** The stage's road is all granted: up to the pickup before it is reported, the whole route after. */
 private static String endOfStage(int last,List<Long> route){
  return last<route.size()-1?"AUTHORIZED_TO_PICKUP":"ROUTE_COMPLETE";
 }
 private static OffsetDateTime staleBefore(OperationOptions options){
  return OffsetDateTime.now().minusSeconds(options.positionStaleSeconds());
 }
 static boolean isStopNode(MapSnapshot map,long nodeId){
  for(var node:map.nodes()) if(node.id()==nodeId) return node.stopNode();
  return false;
 }
 private static double length(MapSnapshot map,List<Long> nodeIds){
  var byId=new HashMap<Long,MapSnapshot.Node>();
  for(var node:map.nodes()) byId.put(node.id(),node);
  double sum=0;
  for(int i=1;i<nodeIds.size();i++){
   var from=byId.get(nodeIds.get(i-1)); var to=byId.get(nodeIds.get(i));
   if(from==null||to==null) continue;
   sum+=Math.hypot(to.x()-from.x(),to.y()-from.y());
  }
  return sum;
 }
 private static int indexOfNode(List<NodeHold> holds,long nodeId){
  for(int i=0;i<holds.size();i++) if(holds.get(i).nodeId()==nodeId) return i;
  return -1;
 }
 private static Extension none(long orderId,long vehicleId,String decision){
  return new Extension(orderId,vehicleId,decision,List.of(),null);
 }
 private String payload(VehicleSnapshot vehicle,List<Long> authorized,List<Long> added,boolean complete){
  var body=new LinkedHashMap<String,Object>();
  body.put("vehicleId",vehicle.id()); body.put("vehicleCode",vehicle.code());
  body.put("fromNodeId",vehicle.nodeId());
  body.put("authorizedNodeIds",authorized);
  body.put("addedNodeIds",added);
  body.put("authorityComplete",complete);
  try{ return json.writeValueAsString(body); }
  catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }
}
