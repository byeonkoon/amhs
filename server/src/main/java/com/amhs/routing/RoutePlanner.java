package com.amhs.routing;

import com.amhs.topology.MapSnapshot;
import java.util.*;
import static com.amhs.routing.RouteResult.Status.*;
import static com.amhs.routing.RouteResult.Reason.*;

/** Directed shortest paths on immutable snapshots. A route is NOT entry permission. */
public final class RoutePlanner {
    private record Step(MapSnapshot.Edge edge,double cost) {}
    private record Visit(long node,double cost) {}

    /** Static-cost query for callers that do not model traffic. Failure reasons are preserved. */
    public RouteResult find(MapSnapshot map,long start,long goal) {
        return find(new RouteQuery(map,start,goal,EdgeCostPolicy.staticCost()));
    }

    public RouteResult find(RouteQuery query) {
        var map=query.map(); var nodes=new HashMap<Long,MapSnapshot.Node>();
        for(var node:map.nodes()) if(nodes.put(node.id(),node)!=null) throw new IllegalArgumentException("DUPLICATE_NODE");
        var outgoing=new HashMap<Long,List<Step>>(); var edgeIds=new HashSet<Long>();
        for(var edge:map.edges()) {
            if(!edgeIds.add(edge.id())||!Double.isFinite(edge.cost())||edge.cost()<0
                ||!nodes.containsKey(edge.fromNodeId())||!nodes.containsKey(edge.toNodeId()))
                throw new IllegalArgumentException("INVALID_EDGE");
            if(!edge.enabled()||!nodes.get(edge.fromNodeId()).enabled()||!nodes.get(edge.toNodeId()).enabled()) continue;
            double cost=query.costPolicy().seconds(edge);
            if(!Double.isFinite(cost)||cost<0) throw new IllegalArgumentException("INVALID_EFFECTIVE_COST");
            outgoing.computeIfAbsent(edge.fromNodeId(),id->new ArrayList<>()).add(new Step(edge,cost));
        }
        if(query.startNodeId()==null||!nodes.containsKey(query.startNodeId())) return unreachable(map,START_UNKNOWN);
        if(query.goalNodeId()==null||!nodes.containsKey(query.goalNodeId())) return unreachable(map,GOAL_UNKNOWN);
        long start=query.startNodeId(),goal=query.goalNodeId();
        if(!nodes.get(start).enabled()) return unreachable(map,START_BLOCKED);
        if(!nodes.get(goal).enabled()) return unreachable(map,GOAL_BLOCKED);
        if(start==goal) return new RouteResult(map.siteId(),map.revisionId(),SAME_NODE,null,List.of(start),List.of(),0d);
        outgoing.values().forEach(list->list.sort(Comparator.comparingDouble(Step::cost)
            .thenComparingLong(s->s.edge().toNodeId()).thenComparingLong(s->s.edge().id())));
        var distance=new HashMap<Long,Double>(); var previous=new HashMap<Long,MapSnapshot.Edge>();
        var queue=new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparingLong(Visit::node));
        distance.put(start,0d); queue.add(new Visit(start,0));
        while(!queue.isEmpty()) {
            var visit=queue.remove();
            if(visit.cost()>distance.getOrDefault(visit.node(),Double.POSITIVE_INFINITY)) continue;
            if(visit.node()==goal) {
                var path=new LinkedList<Long>(); var segments=new LinkedList<Long>();
                long cursor=goal; path.addFirst(cursor);
                while(cursor!=start) { var edge=previous.get(cursor); segments.addFirst(edge.id()); cursor=edge.fromNodeId(); path.addFirst(cursor); }
                return new RouteResult(map.siteId(),map.revisionId(),FOUND,null,path,segments,visit.cost());
            }
            for(var step:outgoing.getOrDefault(visit.node(),List.of())) {
                double next=visit.cost()+step.cost();
                if(!Double.isFinite(next)) throw new IllegalArgumentException("ROUTE_COST_OVERFLOW");
                long destination=step.edge().toNodeId();
                if(next<distance.getOrDefault(destination,Double.POSITIVE_INFINITY)) {
                    distance.put(destination,next); previous.put(destination,step.edge()); queue.add(new Visit(destination,next));
                }
            }
        }
        return unreachable(map,NO_PATH);
    }
    private RouteResult unreachable(MapSnapshot map,RouteResult.Reason reason) {
        return new RouteResult(map.siteId(),map.revisionId(),UNREACHABLE,reason,List.of(),List.of(),null);
    }
}
