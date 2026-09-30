package com.amhs.transport.api;
import com.amhs.transport.ContinuousMovement;
import com.amhs.transport.SiteSweep;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
/**
 * Extends movement authority for work already under way. Asking and being told nothing was
 * needed is the ordinary answer, so each result carries the reason rather than an error.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}")
class MovementController {
    private final ContinuousMovement movement; private final SiteSweep sweep;
    MovementController(ContinuousMovement movement,SiteSweep sweep) { this.movement=movement; this.sweep=sweep; }
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
}
