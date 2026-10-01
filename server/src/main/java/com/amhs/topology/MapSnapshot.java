package com.amhs.topology;
import java.util.List;
import com.amhs.platform.VehicleType;
public record MapSnapshot(long siteId, long revisionId, List<Node> nodes, List<Edge> edges, List<Port> ports) {
    public MapSnapshot {
        nodes=List.copyOf(nodes); edges=List.copyOf(edges); ports=List.copyOf(ports);
    }
    /**
     * {@code stopNode} ends every movement authority that reaches it. {@code mergePoint} marks where
     * tracks join, so entry there is decided first come first served.
     */
    public record Node(long id, String code, double x, double y, boolean enabled, boolean stopNode, boolean mergePoint) {
        public Node(long id,String code,double x,double y,boolean enabled) { this(id,code,x,y,enabled,false,false); }
        public Node(long id,String code,double x,double y) { this(id,code,x,y,true); }
    }
    public record Edge(long id, long fromNodeId, long toNodeId, double cost, boolean enabled) {}
    public record Port(long id, String code, String portType, VehicleType accessVehicleType, long linkedNodeId) {}
}
