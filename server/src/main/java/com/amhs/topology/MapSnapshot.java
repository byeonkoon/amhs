package com.amhs.topology;
import java.util.List;
import com.amhs.platform.VehicleType;
public record MapSnapshot(long siteId, long revisionId, List<Node> nodes, List<Edge> edges, List<Port> ports) {
    public MapSnapshot {
        nodes=List.copyOf(nodes); edges=List.copyOf(edges); ports=List.copyOf(ports);
    }
    /**
     * {@code stopNode} ends every movement authority that reaches it. {@code mergePoint} marks where
     * tracks join, so entry there is decided first come first served. {@code chargePoint}: an AGV
     * can charge here.
     */
    public record Node(long id, String code, double x, double y, boolean enabled, boolean stopNode, boolean mergePoint,
                       boolean chargePoint) {
        public Node(long id,String code,double x,double y,boolean enabled,boolean stopNode,boolean mergePoint) {
            this(id,code,x,y,enabled,stopNode,mergePoint,false);
        }
        public Node(long id,String code,double x,double y,boolean enabled) { this(id,code,x,y,enabled,false,false); }
        public Node(long id,String code,double x,double y) { this(id,code,x,y,true); }
    }
    /** {@code curveMm}: see {@link EdgeSettings}; 0 is a straight segment. */
    public record Edge(long id, long fromNodeId, long toNodeId, double cost, boolean enabled, double curveMm) {
        public Edge(long id,long fromNodeId,long toNodeId,double cost,boolean enabled) { this(id,fromNodeId,toNodeId,cost,enabled,0); }
    }
    /** The equipment the port belongs to, when one is linked: its id, code and name. */
    public record Port(long id, String code, String portType, VehicleType accessVehicleType, long linkedNodeId,
                       Long equipmentId, String equipmentCode, String equipmentName) {
        public Port(long id,String code,String portType,VehicleType accessVehicleType,long linkedNodeId) {
            this(id,code,portType,accessVehicleType,linkedNodeId,null,null,null);
        }
    }
}
