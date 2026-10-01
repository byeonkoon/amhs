package com.amhs.traffic.api;
import com.amhs.traffic.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** Read-only view of who may occupy what, and of chains that cannot resolve themselves. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/traffic")
class TrafficController {
    private final TrafficControl traffic;
    TrafficController(TrafficControl traffic) { this.traffic=traffic; }
    @GetMapping("/reservations")
    List<NodeHold> held(@PathVariable long siteId,@RequestParam(required=false) Long vehicleId) {
        return vehicleId==null?traffic.held(siteId):traffic.heldBy(siteId,vehicleId);
    }
    /** Capacity and the vehicles inside or already admitted, so a full bay is visible before it blocks anyone. */
    @GetMapping("/zones")
    List<ZoneOccupancy> zones(@PathVariable long siteId) { return traffic.zones(siteId); }
    /** Reported, never resolved here: backing a vehicle out is a movement with its own command. */
    @GetMapping("/deadlocks")
    List<Deadlock> deadlocks(@PathVariable long siteId) { return traffic.deadlocks(siteId); }
}
