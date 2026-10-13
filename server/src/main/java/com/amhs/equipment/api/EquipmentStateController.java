package com.amhs.equipment.api;
import com.amhs.equipment.EquipmentStates;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * What the equipment is doing, for the screens to show.
 *
 * <p>Not under {@code /configuration/}: this is monitoring, and reading it is as open as reading
 * anything else here. Changing how a unit is reached is the administrator's and lives next door.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/equipment")
class EquipmentStateController {
 private final EquipmentStates states;
 EquipmentStateController(EquipmentStates states){ this.states=states; }

 @GetMapping("/states") List<EquipmentStates.State> list(@PathVariable long siteId){ return states.list(siteId); }
}
