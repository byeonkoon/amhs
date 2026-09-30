package com.amhs.transport.api;
import com.amhs.transport.ContinuousMovement;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/**
 * Extends movement authority for work already under way. Asking and being told nothing was
 * needed is the ordinary answer, so each result carries the reason rather than an error.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/movement-authority")
class MovementController {
    private final ContinuousMovement movement;
    MovementController(ContinuousMovement movement) { this.movement=movement; }
    @PostMapping
    List<ContinuousMovement.Extension> extendAll(@PathVariable long siteId) { return movement.extendAll(siteId); }
    @PostMapping("/{orderId}")
    ContinuousMovement.Extension extend(@PathVariable long siteId,@PathVariable long orderId) {
        return movement.extend(siteId,orderId);
    }
}
