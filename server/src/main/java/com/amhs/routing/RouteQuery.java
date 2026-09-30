package com.amhs.routing;
import com.amhs.topology.MapSnapshot;
import java.util.Objects;
public record RouteQuery(MapSnapshot map, Long startNodeId, Long goalNodeId, EdgeCostPolicy costPolicy) {
    public RouteQuery { Objects.requireNonNull(map); Objects.requireNonNull(costPolicy); }
}
