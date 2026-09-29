package com.amhs.fleet.api;
import com.amhs.fleet.VehicleView;
import com.amhs.platform.VehicleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
class FleetController {
    private final JdbcTemplate jdbc;
    FleetController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping("/api/v1/sites/{siteId}/vehicles")
    List<VehicleView> vehicles(@PathVariable long siteId) {
        return jdbc.query("SELECT id,code,v_type,initial_node_id,enabled,assignment_enabled FROM fleet.vehicle_profile WHERE site_id=? ORDER BY id",
            (r,n)->new VehicleView(r.getLong(1),r.getString(2),VehicleType.valueOf(r.getString(3)),r.getLong(4),r.getBoolean(5),r.getBoolean(6),"NOT_CONNECTED","INITIAL_CONFIGURATION"),siteId);
    }
}
