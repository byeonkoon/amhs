package com.amhs.routing;
import java.util.List;
import java.util.Objects;
/** A route proposal, never a reservation or permission to move. */
public record RouteResult(long siteId, long revisionId, Status status, Reason reason,
                          List<Long> nodeIds, List<Long> edgeIds, Double costSeconds) {
    public enum Status { FOUND, SAME_NODE, UNREACHABLE }
    public enum Reason { START_UNKNOWN, GOAL_UNKNOWN, START_BLOCKED, GOAL_BLOCKED, NO_PATH }
    /**
     * The shape is checked here because reservations and movement units are built from these
     * lists. A result claiming FOUND while carrying no segments would be claimed as an empty
     * reservation instead of rejected, so the contradiction is rejected at construction.
     */
    public RouteResult {
        Objects.requireNonNull(status,"status");
        nodeIds=List.copyOf(nodeIds); edgeIds=List.copyOf(edgeIds);
        if((status==Status.UNREACHABLE)!=(reason!=null)) throw new IllegalArgumentException("REASON_ONLY_WHEN_UNREACHABLE");
        if((status==Status.UNREACHABLE)!=(costSeconds==null)) throw new IllegalArgumentException("COST_ONLY_WHEN_REACHABLE");
        if(costSeconds!=null&&(!Double.isFinite(costSeconds)||costSeconds<0)) throw new IllegalArgumentException("INVALID_ROUTE_COST");
        switch(status) {
            case UNREACHABLE -> { if(!nodeIds.isEmpty()||!edgeIds.isEmpty()) throw new IllegalArgumentException("UNREACHABLE_CARRIES_NO_PATH"); }
            case SAME_NODE -> { if(nodeIds.size()!=1||!edgeIds.isEmpty()||costSeconds!=0d) throw new IllegalArgumentException("SAME_NODE_IS_ONE_NODE_AT_ZERO_COST"); }
            case FOUND -> { if(nodeIds.size()<2||edgeIds.size()!=nodeIds.size()-1) throw new IllegalArgumentException("FOUND_NEEDS_ONE_EDGE_BETWEEN_EACH_NODE_PAIR"); }
        }
    }
    public boolean reachable() { return status!=Status.UNREACHABLE; }
}
