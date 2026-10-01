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
 * waiting for it. So an idle vehicle with no order, standing in a zone, is given a move to the
 * nearest place outside where it is not in the way: in no zone, not a merge point, not a port, not
 * where another vehicle stands or holds.
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
  var open=new HashMap<Long,Relocation>();
  for(var relocation:ledger.openRelocations(siteId)) open.put(relocation.vehicleId(),relocation);
  var vehicles=fleet.snapshot(siteId,revisionId);
  var assigned=new HashSet<>(ledger.assignedVehicleIds(siteId));
  var cutoff=OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());

  var out=new ArrayList<Step>();
  for(var vehicle:vehicles){
   var relocation=open.get(vehicle.id());
   Long zone=vehicle.nodeId()==null?null:zoneOf.get(vehicle.nodeId());
   if(relocation==null&&zone==null) continue;
   out.add(step(siteId,revisionId,vehicle,relocation,zone,zoneOf.keySet(),assigned,vehicles,cutoff));
  }
  return out;
 }

 private Step step(long siteId,long revisionId,VehicleSnapshot vehicle,Relocation relocation,Long zone,
                   Set<Long> zoneNodes,Set<Long> assigned,List<VehicleSnapshot> vehicles,OffsetDateTime cutoff){
  long vehicleId=vehicle.id();
  // A vehicle holding an order leaves with that order; a second movement would cross it.
  if(assigned.contains(vehicleId)){
   if(relocation!=null) ledger.finishRelocation(relocation.id(),"ORDER_TOOK_OVER");
   return none(vehicleId,"HAS_ORDER");
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
   var place=nearestClearPlace(siteId,map,zoneNodes,vehicle,vehicles);
   if(place==null) return none(vehicleId,"NO_PLACE_OUTSIDE");
   relocation=ledger.startRelocation(siteId,revisionId,vehicleId,zone,place.nodeIds(),place.nodeIds().getLast());
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
  return new Step(vehicleId,"EVACUATING",added,commandId);
 }

 /**
  * The cheapest place outside every zone that does not stand in anybody's way: not a merge point,
  * which every vehicle joining there has to pass, not a port, which work will come to, and not a
  * node another vehicle stands on or holds. Ties go to the lower node id so the choice repeats.
  */
 private RouteResult nearestClearPlace(long siteId,MapSnapshot map,Set<Long> zoneNodes,VehicleSnapshot vehicle,
                                       List<VehicleSnapshot> vehicles){
  var ports=new HashSet<Long>();
  for(var port:map.ports()) ports.add(port.linkedNodeId());
  var taken=new HashSet<Long>();
  for(var other:vehicles) if(other.id()!=vehicle.id()&&other.nodeId()!=null) taken.add(other.nodeId());
  for(NodeHold hold:traffic.held(siteId)) if(hold.vehicleId()!=vehicle.id()) taken.add(hold.nodeId());
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
  body.put("purpose","ZONE_EVACUATION");
  body.put("fromNodeId",vehicle.nodeId());
  body.put("targetNodeId",relocation.targetNodeId());
  body.put("authorizedNodeIds",grant.nodeIds());
  body.put("addedNodeIds",added);
  body.put("authorityComplete",grant.complete());
  try{ return json.writeValueAsString(body); }
  catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }
}
