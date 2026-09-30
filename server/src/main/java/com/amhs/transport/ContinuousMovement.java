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
import java.util.*;

/**
 * Extends the movement authority of a vehicle that is already moving, so it runs on instead of
 * stopping at the end of what it was granted.
 *
 * <p>The extension and the command that carries it are written together. Nothing records that a
 * command was issued before it exists in the outbox, so a send that fails is retried by the
 * outbox rather than leaving a record claiming the vehicle was already told.
 *
 * <p>A second extension is never produced while one is still unacknowledged. That, rather than a
 * fixed wait between sends, is the condition worth checking: what must not happen is two
 * overlapping extensions, and time only ever approximated that.
 */
@Service
public class ContinuousMovement {
 private final TransportLedger ledger; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox; private final TopologyQuery topology;
 private final OperationOptionConfiguration operations; private final ObjectMapper json;
 public ContinuousMovement(TransportLedger ledger,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,
                           TopologyQuery topology,OperationOptionConfiguration operations,ObjectMapper json){
  this.ledger=ledger; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox;
  this.topology=topology; this.operations=operations; this.json=json;
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

  OperationOptions options=operations.get(siteId).settings();
  if(!options.moreEnabled()) return none(orderId,vehicleId,"MORE_DISABLED");

  var vehicle=fleet.find(siteId,order.revisionId(),vehicleId);
  if(vehicle.isEmpty()) return none(orderId,vehicleId,"VEHICLE_NOT_FOUND");
  var state=vehicle.get();
  if(state.nodeId()==null) return none(orderId,vehicleId,"POSITION_UNKNOWN");
  if(!state.online()||!state.remote()) return none(orderId,vehicleId,"VEHICLE_NOT_CONTROLLABLE");
  // MORE lengthens a movement in progress. A stopped vehicle needs a movement command, not an
  // extension of one it is no longer running.
  if(state.idle()) return none(orderId,vehicleId,"VEHICLE_NOT_MOVING");

  if(!outbox.unresolved(siteId,orderId,"MORE").isEmpty()) return none(orderId,vehicleId,"MORE_OUTSTANDING");

  var held=traffic.heldBy(siteId,vehicleId);
  int heldIndex=indexOfNode(held,state.nodeId());
  if(heldIndex<0) return none(orderId,vehicleId,"POSITION_OUTSIDE_AUTHORITY");

  var route=ledger.plannedRoute(orderId);
  int routeIndex=route.indexOf(state.nodeId());
  if(routeIndex<0) return none(orderId,vehicleId,"POSITION_OFF_ROUTE");
  var remainingRoute=route.subList(routeIndex,route.size());
  var heldFromHere=held.subList(heldIndex,held.size()).stream().map(NodeHold::nodeId).toList();
  if(heldFromHere.size()>=remainingRoute.size()) return none(orderId,vehicleId,"ROUTE_COMPLETE");

  if(!thresholdReached(siteId,order.revisionId(),options,heldFromHere,remainingRoute))
   return none(orderId,vehicleId,"NOT_NEEDED");

  var grant=traffic.request(siteId,order.revisionId(),vehicleId,orderId,remainingRoute);
  var added=new ArrayList<>(grant.nodeIds());
  added.removeAll(heldFromHere);
  // Asking and getting nothing new is congestion, not an error: the vehicle keeps the authority
  // it has and stops at its end if nothing frees up before it gets there.
  if(added.isEmpty()) return none(orderId,vehicleId,grant.reason()==null?"NOTHING_TO_ADD":grant.reason());

  var extended=traffic.heldBy(siteId,vehicleId);
  String commandId="order-"+orderId+"-more-seq-"+extended.getLast().sequence();
  outbox.enqueue(orderId,commandId,"MORE",payload(state,grant.nodeIds(),added,grant.complete()));
  return new Extension(orderId,vehicleId,"EXTENDED",added,commandId);
 }

 /** One pass over everything currently assigned at the site. */
 @Transactional
 public List<Extension> extendAll(long siteId){
  var out=new ArrayList<Extension>();
  for(long orderId:ledger.assignedOrderIds(siteId)) out.add(extend(siteId,orderId));
  return out;
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
 private boolean thresholdReached(long siteId,long revisionId,OperationOptions options,
                                  List<Long> heldFromHere,List<Long> remainingRoute){
  if("NODE".equals(options.moreType())) return heldFromHere.size()-1<=options.moreRemainingNodes();
  var map=topology.getMap(siteId,revisionId);
  var window=remainingRoute.subList(0,Math.min(remainingRoute.size(),options.nodeCount()));
  double full=length(map,window);
  if(full<=0) return true;
  return length(map,heldFromHere)*100<=(double)options.moreRemainingPercent()*full;
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
