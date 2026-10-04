package com.amhs.transport.api;
import com.amhs.transport.ContinuousMovement;
import com.amhs.transport.SiteSweep;
import com.amhs.transport.TransportRejected;
import com.amhs.transport.ZoneEvacuation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * Extends movement authority for work already under way. Asking and being told nothing was
 * needed is the ordinary answer, so each result carries the reason rather than an error.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}")
class MovementController {
    private final ContinuousMovement movement; private final SiteSweep sweep; private final ZoneEvacuation moves;
    MovementController(ContinuousMovement movement,SiteSweep sweep,ZoneEvacuation moves) {
        this.movement=movement; this.sweep=sweep; this.moves=moves;
    }
    @PostMapping("/movement-authority")
    List<ContinuousMovement.Extension> extendAll(@PathVariable long siteId) { return movement.extendAll(siteId); }
    @PostMapping("/movement-authority/{orderId}")
    ContinuousMovement.Extension extend(@PathVariable long siteId,@PathVariable long orderId) {
        return movement.extend(siteId,orderId);
    }
    /** One pass of the execution loop: take on work, extend it, send what that produced. */
    @PostMapping("/operation-sweep")
    SiteSweep.Sweep sweep(@PathVariable long siteId) {
        return sweep.run(siteId,"api",UUID.randomUUID().toString());
    }

    /**
     * An operator sends a vehicle to a node. Refused unless the vehicle is in use, under remote
     * control, idle and free of any order or other move; the first stretch is commanded at once.
     */
    @PostMapping("/vehicles/{vehicleId}/move")
    ZoneEvacuation.Step move(@PathVariable long siteId,@PathVariable long vehicleId,@RequestBody Map<String,Object> body) {
        if(!(body.get("targetNodeId") instanceof Number target))
            throw new TransportRejected("TARGET_NODE_INVALID","targetNodeId is required",false);
        return moves.moveTo(siteId,vehicleId,target.longValue());
    }
    /**
     * An operator sends a vehicle to charge now, or stops a charge. {@code charge} is true to
     * start. The thresholds are the controller's own rule; this is a person deciding instead.
     */
    @PostMapping("/vehicles/{vehicleId}/charge")
    ZoneEvacuation.Step charge(@PathVariable long siteId,@PathVariable long vehicleId,@RequestBody Map<String,Object> body) {
        if(!(body.get("charge") instanceof Boolean start))
            throw new TransportRejected("CHARGE_INVALID","charge is required",false);
        return moves.chargeNow(siteId,vehicleId,start);
    }
    /** Stops a vehicle's open move: no more road is granted, and it closes once the vehicle has stopped. */
    @PostMapping("/vehicles/{vehicleId}/move/stop")
    ZoneEvacuation.Step stop(@PathVariable long siteId,@PathVariable long vehicleId) {
        return moves.stopMove(siteId,vehicleId);
    }
    /** Where every vehicle with work or a move is going, and the nodes still ahead of it. */
    @GetMapping("/vehicle-headings")
    java.util.List<ZoneEvacuation.Heading> headings(@PathVariable long siteId) {
        return moves.headings(siteId);
    }
    /** Open moves without an order, so a screen can show who is being moved and offer to stop it. */
    @GetMapping("/vehicle-moves")
    java.util.List<com.amhs.transport.Relocation> openMoves(@PathVariable long siteId) {
        return moves.openMoves(siteId);
    }
    @ExceptionHandler(TransportRejected.class)
    ResponseEntity<Map<String,String>> rejected(TransportRejected ex) {
        var status="VEHICLE_NOT_FOUND".equals(ex.code())?HttpStatus.NOT_FOUND
            :ex.isConflict()?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code",ex.code(),"message",ex.getMessage()));
    }
}
