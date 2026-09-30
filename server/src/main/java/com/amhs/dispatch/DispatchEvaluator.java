package com.amhs.dispatch;
import com.amhs.platform.VehicleType;
import com.amhs.topology.MapSnapshot;
import com.amhs.routing.RoutePlanner;
import com.amhs.routing.RouteResult;
import java.util.*;
/** Produces a proposal; transport must revalidate and claim resources atomically. */
public final class DispatchEvaluator {
    public record Candidate(long id, VehicleType vType, long currentNodeId, boolean enabled,
        boolean assignmentEnabled, boolean online, boolean remote, boolean idle) {}
    /** Both legs keep their ordered segments so a claim can be built without replanning. */
    public record Proposal(long vehicleId, RouteResult toSource, RouteResult toDestination, double cost) {}
    public record Result(String reason, Optional<Proposal> proposal) {}
    public Result evaluate(MapSnapshot map, long sourcePortId, long destinationPortId, List<Candidate> candidates) {
        var source=map.ports().stream().filter(p->p.id()==sourcePortId).findFirst();
        var dest=map.ports().stream().filter(p->p.id()==destinationPortId).findFirst();
        if(source.isEmpty()||dest.isEmpty()) return new Result("PORT_NOT_FOUND",Optional.empty());
        if(sourcePortId==destinationPortId) return new Result("SAME_PORT",Optional.empty());
        if(source.get().accessVehicleType()!=dest.get().accessVehicleType()) return new Result("PORT_TYPE_MISMATCH",Optional.empty());
        var available=candidates.stream().filter(c->c.vType()==source.get().accessVehicleType()&&c.enabled()&&c.assignmentEnabled()&&c.online()&&c.remote()&&c.idle()).toList();
        if(available.isEmpty()) return new Result("NO_AVAILABLE_VEHICLE",Optional.empty());
        var planner=new RoutePlanner();
        // The loaded leg does not depend on which vehicle is chosen, so it is planned once.
        // Separating it also separates two faults an operator must act on differently: a map
        // that cannot deliver at all, and a fleet that cannot reach the pickup.
        var delivery=planner.find(map,source.get().linkedNodeId(),dest.get().linkedNodeId());
        if(!delivery.reachable()) return new Result("DELIVERY_ROUTE_"+delivery.reason(),Optional.empty());
        var proposals=new ArrayList<Proposal>(); var approachFailures=EnumSet.noneOf(RouteResult.Reason.class);
        for(var c:available) {
            var approach=planner.find(map,c.currentNodeId(),source.get().linkedNodeId());
            if(approach.reachable()) proposals.add(new Proposal(c.id(),approach,delivery,approach.costSeconds()+delivery.costSeconds()));
            else approachFailures.add(approach.reason());
        }
        var best=proposals.stream().min(Comparator.comparingDouble(Proposal::cost).thenComparingLong(Proposal::vehicleId));
        if(best.isPresent()) return new Result("PROPOSED",best);
        // One vehicle's story stands for the fleet only when every vehicle failed the same way.
        return new Result(approachFailures.size()==1?"APPROACH_ROUTE_"+approachFailures.iterator().next():"NO_REACHABLE_VEHICLE",Optional.empty());
    }
}
