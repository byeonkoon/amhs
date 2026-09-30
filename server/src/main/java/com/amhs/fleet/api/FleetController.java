package com.amhs.fleet.api;
import com.amhs.fleet.VehicleView;
import com.amhs.platform.VehicleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
@RestController
class FleetController {
    private final JdbcTemplate jdbc;
    FleetController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping("/api/v1/sites/{siteId}/vehicles")
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    // Must stay public: Spring's proxy applies @Transactional only to public methods and
    // silently ignores it otherwise, which would drop the isolation this query relies on.
    public List<VehicleView> vehicles(@PathVariable long siteId, @RequestParam(required=false) Long revisionId) {
        var revisions = revisionId == null
            ? jdbc.queryForList("SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'", Long.class, siteId)
            : jdbc.queryForList("SELECT id FROM topology.map_revision WHERE site_id=? AND id=?", Long.class, siteId, revisionId);
        if (revisions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAP_NOT_FOUND");
        return jdbc.query("SELECT id,code,v_type,initial_node_id,enabled,assignment_enabled FROM fleet.vehicle_profile WHERE site_id=? AND revision_id=? ORDER BY id",
            (r,n)->new VehicleView(r.getLong(1),r.getString(2),VehicleType.valueOf(r.getString(3)),r.getLong(4),r.getBoolean(5),r.getBoolean(6),"NOT_CONNECTED","INITIAL_CONFIGURATION"),siteId,revisions.getFirst());
    }
}
