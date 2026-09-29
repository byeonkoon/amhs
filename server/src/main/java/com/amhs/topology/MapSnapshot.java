package com.amhs.topology;
import java.util.List;
import com.amhs.platform.VehicleType;
public record MapSnapshot(long siteId, long revisionId, List<Node> nodes, List<Edge> edges, List<Port> ports) {
    public record Node(long id, String code, double x, double y) {}
    public record Edge(long id, long fromNodeId, long toNodeId, double cost, boolean enabled) {}
    public record Port(long id, String code, String portType, String makerType, VehicleType accessVehicleType, long linkedNodeId) {}
}
