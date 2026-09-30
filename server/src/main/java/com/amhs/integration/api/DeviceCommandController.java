package com.amhs.integration.api;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceCommand;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** Read-only visibility. Commands nobody can settle automatically need an operator to see them. */
@RestController
class DeviceCommandController {
    private final CommandOutbox outbox;
    DeviceCommandController(CommandOutbox outbox) { this.outbox=outbox; }
    @GetMapping("/api/v1/sites/{siteId}/transport-orders/{orderId}/device-commands")
    List<DeviceCommand> byOrder(@PathVariable long siteId,@PathVariable long orderId) {
        return outbox.byOrder(siteId,orderId);
    }
    @GetMapping("/api/v1/sites/{siteId}/device-commands/in-doubt")
    List<DeviceCommand> inDoubt(@PathVariable long siteId,@RequestParam(defaultValue="50") int limit) {
        return outbox.inDoubt(siteId,limit);
    }
}
