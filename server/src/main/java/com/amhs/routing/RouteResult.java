package com.amhs.routing;
import java.util.List;
/** A route proposal, never a reservation or permission to move. */
public record RouteResult(long siteId, long revisionId, Status status, Reason reason,
                          List<Long> nodeIds, List<Long> edgeIds, Double costSeconds) {
    public enum Status { FOUND, SAME_NODE, UNREACHABLE }
    public enum Reason { START_UNKNOWN, GOAL_UNKNOWN, START_BLOCKED, GOAL_BLOCKED, NO_PATH }
    public RouteResult { nodeIds=List.copyOf(nodeIds); edgeIds=List.copyOf(edgeIds); }
}
