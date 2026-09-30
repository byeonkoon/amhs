package com.amhs.equipment.api;
import com.amhs.equipment.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
/** Equipment, racks and cells. Engineer-level: this configures machines, not the track. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/equipment")
class EquipmentConfigurationController {
    private final EquipmentConfiguration equipment;
    EquipmentConfigurationController(EquipmentConfiguration equipment) { this.equipment=equipment; }

    @GetMapping
    List<ConfigurationRecord<EquipmentSettings>> units(@PathVariable long siteId) { return equipment.units(siteId); }
    @PostMapping
    ConfigurationRecord<EquipmentSettings> create(@PathVariable long siteId,@RequestBody EquipmentSettings settings) {
        return equipment.createUnit(siteId,settings);
    }
    @PutMapping("/{unitId}")
    ConfigurationRecord<EquipmentSettings> update(@PathVariable long siteId,@PathVariable long unitId,
            @RequestParam long expectedVersion,@RequestBody EquipmentSettings settings) {
        return equipment.updateUnit(siteId,unitId,expectedVersion,settings);
    }

    @GetMapping("/{unitId}/racks")
    List<ConfigurationRecord<RackSettings>> racks(@PathVariable long siteId,@PathVariable long unitId) {
        return equipment.racks(siteId,unitId);
    }
    @PostMapping("/{unitId}/racks")
    ConfigurationRecord<RackSettings> createRack(@PathVariable long siteId,@PathVariable long unitId,
            @RequestBody RackSettings settings) {
        return equipment.createRack(siteId,unitId,settings);
    }
    @PutMapping("/{unitId}/racks/{rackId}")
    ConfigurationRecord<RackSettings> updateRack(@PathVariable long siteId,@PathVariable long unitId,
            @PathVariable long rackId,@RequestParam long expectedVersion,@RequestBody RackSettings settings) {
        return equipment.updateRack(siteId,unitId,rackId,expectedVersion,settings);
    }

    @GetMapping("/{unitId}/racks/{rackId}/slots")
    List<SlotView> slots(@PathVariable long siteId,@PathVariable long unitId,@PathVariable long rackId) {
        return equipment.slots(siteId,unitId,rackId);
    }
    /** Marks a cell as not to be filled, or clears that. Deliberately not an occupancy call. */
    @PutMapping("/{unitId}/racks/{rackId}/slots/{slotId}")
    SlotView setUsable(@PathVariable long siteId,@PathVariable long unitId,@PathVariable long rackId,
            @PathVariable long slotId,@RequestBody Map<String,Object> body) {
        boolean usable=Boolean.TRUE.equals(body.get("usable"));
        Object reason=body.get("unusableReason");
        return equipment.setSlotUsable(siteId,unitId,rackId,slotId,usable,reason==null?null:String.valueOf(reason));
    }
}
