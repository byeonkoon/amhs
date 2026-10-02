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
    private final FleetState fleet; private final com.amhs.traffic.TrafficControl traffic;
    VehicleStateController(FleetState fleet,com.amhs.traffic.TrafficControl traffic) {
        this.fleet=fleet; this.traffic=traffic;
    }
    @GetMapping
    List<VehicleSnapshot> list(@PathVariable long siteId,@PathVariable long revisionId) {
        return fleet.snapshot(siteId,revisionId);
    }
    @PutMapping("/{vehicleId}")
    Map<String,Object> report(@PathVariable long siteId,@PathVariable long revisionId,@PathVariable long vehicleId,
                              @RequestBody Body report) {
        // Only a device may claim to be a device. Everything entered by hand is marked simulated.
        var source="DEVICE".equals(report.source())?"DEVICE":"SIMULATED";
        var stored=fleet.report(siteId,revisionId,vehicleId,new VehicleReport(report.nodeId(),report.online(),
            report.remote(),report.idle(),report.carrierPresent(),source,report.destinationChangeEnabled(),
            report.mode(),report.status()));
        // An arrival further along is the only thing that frees the nodes behind it, so the
        // release happens here, with the evidence, rather than on a timer somewhere else.
        if(report.batteryPct()!=null){
            fleet.reportBattery(siteId,revisionId,vehicleId,report.batteryPct());
            stored=fleet.find(siteId,revisionId,vehicleId).orElseThrow();
        }
        var progress=stored.nodeId()==null?null:traffic.advanced(siteId,vehicleId,stored.nodeId());
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("vehicle",stored);
        if(progress!=null) body.put("traffic",progress);
        return body;
    }
    /** The report as sent. It becomes a VehicleReport here, so a bad mode or status is a 422, not a parse error. */
    /** {@code batteryPct}: state of charge, percent, when the vehicle reports one. */
    record Body(Long nodeId,boolean online,boolean remote,boolean idle,boolean carrierPresent,String source,
                Boolean destinationChangeEnabled,String mode,String status,Double batteryPct) {}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> rejected(IllegalArgumentException ex) {
        var status="VEHICLE_NOT_FOUND".equals(ex.getMessage())?HttpStatus.NOT_FOUND:HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code",ex.getMessage()));
    }
}
