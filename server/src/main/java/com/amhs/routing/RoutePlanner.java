package com.amhs.routing;
import com.amhs.topology.MapSnapshot;
import java.util.*;
/** Pure directed shortest-path calculation; a route is NOT permission to enter a resource. */
public final class RoutePlanner {
    public record Route(List<Long> nodeIds, double cost) {}
    private record Visit(long node, double cost) {}
    public Optional<Route> find(MapSnapshot map, long start, long goal) {
        var ids = new HashSet<Long>();
        map.nodes().forEach(n -> ids.add(n.id()));
        if (!ids.contains(start) || !ids.contains(goal)) return Optional.empty();
        var outgoing = new HashMap<Long,List<MapSnapshot.Edge>>();
        for (var e:map.edges()) {
            if (!Double.isFinite(e.cost()) || e.cost()<0 || !ids.contains(e.fromNodeId()) || !ids.contains(e.toNodeId()))
                throw new IllegalArgumentException("INVALID_EDGE");
            if (e.enabled()) outgoing.computeIfAbsent(e.fromNodeId(), k->new ArrayList<>()).add(e);
        }
        var distance = new HashMap<Long,Double>(); var previous = new HashMap<Long,Long>();
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparingLong(Visit::node));
        distance.put(start,0d); queue.add(new Visit(start,0));
        while (!queue.isEmpty()) {
            var v=queue.remove();
            if (v.cost()>distance.getOrDefault(v.node(),Double.POSITIVE_INFINITY)) continue;
            if (v.node()==goal) {
                var path=new LinkedList<Long>(); long cursor=goal; path.addFirst(cursor);
                while(cursor!=start) { cursor=previous.get(cursor); path.addFirst(cursor); }
                return Optional.of(new Route(List.copyOf(path),v.cost()));
            }
            for(var e:outgoing.getOrDefault(v.node(),List.of())) {
                double next=v.cost()+e.cost();
                if (next<distance.getOrDefault(e.toNodeId(),Double.POSITIVE_INFINITY)) {
                    distance.put(e.toNodeId(),next); previous.put(e.toNodeId(),v.node()); queue.add(new Visit(e.toNodeId(),next));
                }
            }
        }
        return Optional.empty();
    }
}
