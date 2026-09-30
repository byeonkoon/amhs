package com.amhs.topology.infrastructure;
import com.amhs.topology.*;
import com.amhs.platform.VehicleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
@Service
class JdbcTopologyQuery implements TopologyQuery {
    private final JdbcTemplate jdbc;
    JdbcTopologyQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public MapSnapshot getMap(long siteId, long revisionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM topology.map_revision WHERE site_id=? AND id=?", Integer.class, siteId, revisionId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAP_NOT_FOUND");
        var nodes = jdbc.query("SELECT id,code,x_mm,y_mm,enabled FROM topology.node WHERE site_id=? AND revision_id=? ORDER BY id",
            (r,n)->new MapSnapshot.Node(r.getLong(1),r.getString(2),r.getDouble(3),r.getDouble(4),r.getBoolean(5)), siteId,revisionId);
        var edges = jdbc.query("SELECT id,from_node_id,to_node_id,cost,enabled FROM topology.edge WHERE site_id=? AND revision_id=? ORDER BY id",
            (r,n)->new MapSnapshot.Edge(r.getLong(1),r.getLong(2),r.getLong(3),r.getDouble(4),r.getBoolean(5)), siteId,revisionId);
        var ports = jdbc.query("SELECT id,code,port_type,access_vehicle_type,linked_node_id FROM topology.port WHERE site_id=? AND revision_id=? ORDER BY id",
            (r,n)->new MapSnapshot.Port(r.getLong(1),r.getString(2),r.getString(3),VehicleType.valueOf(r.getString(4)),r.getLong(5)), siteId,revisionId);
        return new MapSnapshot(siteId,revisionId,nodes,edges,ports);
    }
}
