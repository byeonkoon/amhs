package com.amhs.fleet.api;
import com.amhs.fleet.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
/**
 * Last reported vehicle state.
 *
 * <p>Reports arrive here until device adapters exist, and every report records where it came
 * from, so a simulated position is never read back as evidence from a vehicle.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/maps/{revisionId}/vehicle-state")
class VehicleStateController {
    private final FleetState fleet;
    VehicleStateController(FleetState fleet) { this.fleet=fleet; }
    @GetMapping
    List<VehicleSnapshot> list(@PathVariable long siteId,@PathVariable long revisionId) {
        return fleet.snapshot(siteId,revisionId);
    }
    @PutMapping("/{vehicleId}")
    VehicleSnapshot report(@PathVariable long siteId,@PathVariable long revisionId,@PathVariable long vehicleId,
                           @RequestBody VehicleReport report) {
        // Only a device may claim to be a device. Everything entered by hand is marked simulated.
        var source="DEVICE".equals(report.source())?"DEVICE":"SIMULATED";
        return fleet.report(siteId,revisionId,vehicleId,new VehicleReport(report.nodeId(),report.online(),
            report.remote(),report.idle(),report.carrierPresent(),source));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> rejected(IllegalArgumentException ex) {
        var status="VEHICLE_NOT_FOUND".equals(ex.getMessage())?HttpStatus.NOT_FOUND:HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code",ex.getMessage()));
    }
}
