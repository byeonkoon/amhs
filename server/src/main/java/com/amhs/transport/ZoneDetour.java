package com.amhs.transport;
import com.amhs.routing.RoutePlanner;
import com.amhs.topology.MapSnapshot;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.ReservationGrant;
import com.amhs.traffic.TrafficControl;
import com.amhs.traffic.ZoneOccupancy;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Sends a vehicle round the loop when the zone it is heading into is full.
 *
 * <p>A vehicle refused entry must not wait at the branch: on a one-way loop it would stop every
 * vehicle behind it, including the ones that have finished in the zone and need to come back out
 * past it. So the route is lengthened by one lap that stays outside the zone and comes back to the
 * same branch, and the vehicle asks again when it gets there. If the zone is still full it goes
 * round again.
 *
 * <p>The lap is spliced into the stored route rather than computed on the fly, because the vehicle
 * passes the branch node twice and only a stored route with a progress cursor can tell the two
 * visits apart.
 */
@Service
class ZoneDetour {
 private final TransportLedger ledger; private final TrafficControl traffic; private final TopologyQuery topology;
 private final RoutePlanner planner=new RoutePlanner();
 ZoneDetour(TransportLedger ledger,TrafficControl traffic,TopologyQuery topology){
  this.ledger=ledger; this.traffic=traffic; this.topology=topology;
 }

 /**
  * When {@code grant} stopped at the entrance of a full zone, splices a lap into the route and asks
  * again from the same position. Otherwise, or when no lap exists, returns {@code grant} unchanged
  * and the vehicle waits at the entrance.
  *
  * @param status the order's stage, which decides how far the re-asked run may reach
  * @param cursor index of the vehicle's position in the stored route
  */
 ReservationGrant aroundFullZone(long siteId,long revisionId,long vehicleId,long orderId,OrderStatus status,
                                 int cursor,ReservationGrant grant){
  if(!"ZONE_FULL".equals(grant.reason())||grant.nodeIds().isEmpty()) return grant;
  var route=ledger.plannedRoute(orderId);
  int pickup=ledger.pickupIndex(orderId);
  int branch=cursor+grant.nodeIds().size()-1;
  if(branch<0||branch>=route.size()-1) return grant;
  var zoneNodes=new HashSet<Long>();
  for(ZoneOccupancy zone:traffic.zones(siteId)) if(zone.enabled()) zoneNodes.addAll(zone.nodeIds());
  var lap=lap(topology.getMap(siteId,revisionId),zoneNodes,route.get(branch));
  if(lap.isEmpty()) return grant;
  var detoured=new ArrayList<Long>(route.subList(0,branch+1));
  detoured.addAll(lap.get());
  detoured.addAll(route.subList(branch+1,route.size()));
  // The lap goes in after the branch, so a pickup still ahead of it moves back by the lap's length.
  int movedPickup=pickup>branch?pickup+lap.get().size():pickup;
  ledger.replaceRoute(orderId,detoured,movedPickup);
  int last=MovementPhase.lastIndex(status,movedPickup,detoured.size());
  if(last<cursor) return grant;
  return traffic.request(siteId,revisionId,vehicleId,orderId,detoured.subList(cursor,last+1));
 }

 /**
  * The cheapest way from the branch back to itself that never enters a zone. It starts with a
  * step to a non-zone neighbour and ends at the branch, so the branch is not repeated inside it.
  */
 private Optional<List<Long>> lap(MapSnapshot map,Set<Long> zoneNodes,long branch){
  var outside=new MapSnapshot(map.siteId(),map.revisionId(),map.nodes(),
   map.edges().stream().filter(e->!zoneNodes.contains(e.fromNodeId())&&!zoneNodes.contains(e.toNodeId())).toList(),
   map.ports());
  List<Long> best=null; double bestCost=Double.POSITIVE_INFINITY;
  for(var edge:outside.edges()){
   if(edge.fromNodeId()!=branch||!edge.enabled()) continue;
   var back=planner.find(outside,edge.toNodeId(),branch);
   if(back.status()!=com.amhs.routing.RouteResult.Status.FOUND) continue;
   double cost=edge.cost()+back.costSeconds();
   if(cost<bestCost||(cost==bestCost&&best!=null&&edge.toNodeId()<best.getFirst())){
    best=back.nodeIds(); bestCost=cost;
   }
  }
  return Optional.ofNullable(best);
 }
}
