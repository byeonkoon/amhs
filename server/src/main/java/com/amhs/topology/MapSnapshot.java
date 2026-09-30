package com.amhs.topology;
import java.util.List;
import com.amhs.platform.VehicleType;
public record MapSnapshot(long siteId, long revisionId, List<Node> nodes, List<Edge> edges, List<Port> ports) {
    public MapSnapshot {
        nodes=List.copyOf(nodes); edges=List.copyOf(edges); ports=List.copyOf(ports);
    }
    public record Node(long id, String code, double x, double y, boolean enabled) {
        public Node(long id,String code,double x,double y) { this(id,code,x,y,true); }
    }
    public record Edge(long id, long fromNodeId, long toNodeId, double cost, boolean enabled) {}
    public record Port(long id, String code, String portType, VehicleType accessVehicleType, long linkedNodeId) {}
}
