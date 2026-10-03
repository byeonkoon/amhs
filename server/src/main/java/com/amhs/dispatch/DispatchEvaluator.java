package com.amhs.dispatch;
import com.amhs.platform.VehicleType;
import com.amhs.topology.MapSnapshot;
import com.amhs.routing.RoutePlanner;
import com.amhs.routing.RouteQuery;
import com.amhs.routing.RouteResult;
import com.amhs.routing.EdgeCostPolicy;
import java.util.*;
/** Produces a proposal; transport must revalidate and claim resources atomically. */
public final class DispatchEvaluator {
    /** currentNodeId is null when nothing has reported where the vehicle is. */
    /**
     * {@code assignmentEnabled} is how a vehicle is normally used; {@code outOfService} is that
     * something happened to this one. They are kept apart because the answer a dispatcher gives
     * should be the true reason: a vehicle nobody may work is not available, which is not the same
     * as one that was available a moment ago and changed under the lock.
     */
    public record Candidate(long id, VehicleType vType, Long currentNodeId, boolean enabled,
        boolean assignmentEnabled, boolean online, boolean remote, boolean idle, boolean outOfService) {}
    /** Both legs keep their ordered segments so a claim can be built without replanning. */
    public record Proposal(long vehicleId, RouteResult toSource, RouteResult toDestination, double cost) {}
    public record Result(String reason, Optional<Proposal> proposal) {}
    public Result evaluate(MapSnapshot map, long sourcePortId, long destinationPortId, List<Candidate> candidates) {
        var source=map.ports().stream().filter(p->p.id()==sourcePortId).findFirst();
        var dest=map.ports().stream().filter(p->p.id()==destinationPortId).findFirst();
        if(source.isEmpty()||dest.isEmpty()) return new Result("PORT_NOT_FOUND",Optional.empty());
        if(sourcePortId==destinationPortId) return new Result("SAME_PORT",Optional.empty());
        if(source.get().accessVehicleType()!=dest.get().accessVehicleType()) return new Result("PORT_TYPE_MISMATCH",Optional.empty());
        var available=candidates.stream().filter(c->c.vType()==source.get().accessVehicleType()&&c.enabled()&&c.assignmentEnabled()&&!c.outOfService()&&c.online()&&c.remote()&&c.idle()).toList();
        if(available.isEmpty()) return new Result("NO_AVAILABLE_VEHICLE",Optional.empty());
        var planner=new RoutePlanner();
        // The loaded leg does not depend on which vehicle is chosen, so it is planned once.
        // Separating it also separates two faults an operator must act on differently: a map
        // that cannot deliver at all, and a fleet that cannot reach the pickup.
        var delivery=planner.find(map,source.get().linkedNodeId(),dest.get().linkedNodeId());
        if(!delivery.reachable()) return new Result("DELIVERY_ROUTE_"+delivery.reason(),Optional.empty());
        var proposals=new ArrayList<Proposal>(); var approachFailures=EnumSet.noneOf(RouteResult.Reason.class);
        for(var c:available) {
            // The query form is used so an unreported position stays START_UNKNOWN instead of
            // being replaced by some node the vehicle was merely configured at.
            var approach=planner.find(new RouteQuery(map,c.currentNodeId(),source.get().linkedNodeId(),EdgeCostPolicy.staticCost()));
            if(approach.reachable()) proposals.add(new Proposal(c.id(),approach,delivery,approach.costSeconds()+delivery.costSeconds()));
            else approachFailures.add(approach.reason());
        }
        var best=proposals.stream().min(Comparator.comparingDouble(Proposal::cost).thenComparingLong(Proposal::vehicleId));
        if(best.isPresent()) return new Result("PROPOSED",best);
        // One vehicle's story stands for the fleet only when every vehicle failed the same way.
        return new Result(approachFailures.size()==1?"APPROACH_ROUTE_"+approachFailures.iterator().next():"NO_REACHABLE_VEHICLE",Optional.empty());
    }
}
