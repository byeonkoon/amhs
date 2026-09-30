package com.amhs.routing;

import com.amhs.topology.MapSnapshot;
import java.util.Map;

/** Snapshot cost only; occupancy penalties never grant or deny entry. All costs are seconds. */
@FunctionalInterface
public interface EdgeCostPolicy {
    double seconds(MapSnapshot.Edge edge);
    static EdgeCostPolicy staticCost() { return MapSnapshot.Edge::cost; }
    static EdgeCostPolicy trafficAware(Map<Long,Double> edgeLoadFactors, Map<Long,Double> incomingNodePenalties) {
        var factors=Map.copyOf(edgeLoadFactors);
        var penalties=Map.copyOf(incomingNodePenalties);
        factors.values().forEach(v -> { if(!Double.isFinite(v)||v<1) throw new IllegalArgumentException("INVALID_LOAD_FACTOR"); });
        penalties.values().forEach(v -> { if(!Double.isFinite(v)||v<0) throw new IllegalArgumentException("INVALID_NODE_PENALTY"); });
        return edge -> edge.cost()*factors.getOrDefault(edge.id(),1d)+penalties.getOrDefault(edge.toNodeId(),0d);
    }
}
