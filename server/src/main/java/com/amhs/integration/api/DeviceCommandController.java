package com.amhs.integration.api;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceGateway;
import com.amhs.integration.DeviceCommand;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** Read-only visibility. Commands nobody can settle automatically need an operator to see them. */
@RestController
class DeviceCommandController {
    private final CommandOutbox outbox; private final DeviceGateway gateway;
    DeviceCommandController(CommandOutbox outbox,DeviceGateway gateway) { this.outbox=outbox; this.gateway=gateway; }
    @GetMapping("/api/v1/sites/{siteId}/transport-orders/{orderId}/device-commands")
    List<DeviceCommand> byOrder(@PathVariable long siteId,@PathVariable long orderId) {
        return outbox.byOrder(siteId,orderId);
    }
    /**
     * Flushes what is waiting to be sent. Until a site execution loop exists this is how an
     * operator releases a queue, and it reports the outcome counts rather than a bare 200.
     */
    @PostMapping("/api/v1/sites/{siteId}/device-commands/publish")
    DeviceGateway.Publication publish(@PathVariable long siteId) { return gateway.publishPending(siteId); }

    @GetMapping("/api/v1/sites/{siteId}/device-commands/in-doubt")
    List<DeviceCommand> inDoubt(@PathVariable long siteId,@RequestParam(defaultValue="50") int limit) {
        return outbox.inDoubt(siteId,limit);
    }
}
