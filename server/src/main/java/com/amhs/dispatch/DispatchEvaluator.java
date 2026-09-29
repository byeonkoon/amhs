package com.amhs.dispatch;
import com.amhs.platform.VehicleType;
import com.amhs.topology.MapSnapshot;
import com.amhs.routing.RoutePlanner;
import java.util.*;
/** Produces a proposal; transport must revalidate and claim resources atomically. */
public final class DispatchEvaluator {
    public record Candidate(long id, VehicleType vType, long currentNodeId, boolean enabled,
        boolean assignmentEnabled, boolean online, boolean remote, boolean idle) {}
    public record Proposal(long vehicleId, RoutePlanner.Route toSource, RoutePlanner.Route toDestination, double cost) {}
    public record Result(String reason, Optional<Proposal> proposal) {}
    public Result evaluate(MapSnapshot map, long sourcePortId, long destinationPortId, List<Candidate> candidates) {
        var source=map.ports().stream().filter(p->p.id()==sourcePortId).findFirst();
        var dest=map.ports().stream().filter(p->p.id()==destinationPortId).findFirst();
        if(source.isEmpty()||dest.isEmpty()) return new Result("PORT_NOT_FOUND",Optional.empty());
        if(sourcePortId==destinationPortId) return new Result("SAME_PORT",Optional.empty());
        if(source.get().accessVehicleType()!=dest.get().accessVehicleType()) return new Result("PORT_TYPE_MISMATCH",Optional.empty());
        var available=candidates.stream().filter(c->c.vType()==source.get().accessVehicleType()&&c.enabled()&&c.assignmentEnabled()&&c.online()&&c.remote()&&c.idle()).toList();
        if(available.isEmpty()) return new Result("NO_AVAILABLE_VEHICLE",Optional.empty());
        var planner=new RoutePlanner(); var proposals=new ArrayList<Proposal>();
        for(var c:available) {
            var first=planner.find(map,c.currentNodeId(),source.get().linkedNodeId());
            var second=planner.find(map,source.get().linkedNodeId(),dest.get().linkedNodeId());
            if(first.isPresent()&&second.isPresent()) proposals.add(new Proposal(c.id(),first.get(),second.get(),first.get().cost()+second.get().cost()));
        }
        var best=proposals.stream().min(Comparator.comparingDouble(Proposal::cost).thenComparingLong(Proposal::vehicleId));
        return new Result(best.isPresent()?"PROPOSED":"NO_REACHABLE_VEHICLE",best);
    }
}
