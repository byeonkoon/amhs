package com.amhs.equipment.api;
import com.amhs.equipment.EquipmentCommunicationConfiguration;
import com.amhs.equipment.EquipmentCommunicationSettings;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Which equipment is wired to what. Under {@code /configuration/}, so it is the administrator's. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/equipment-communications")
class EquipmentCommunicationController {
 private final EquipmentCommunicationConfiguration service;
 EquipmentCommunicationController(EquipmentCommunicationConfiguration service){ this.service=service; }

 @GetMapping List<ConfigurationRecord<EquipmentCommunicationSettings>> list(@PathVariable long siteId){
  return service.list(siteId);
 }
 @GetMapping("/{id}") ConfigurationRecord<EquipmentCommunicationSettings> get(@PathVariable long siteId,@PathVariable long id){
  return service.get(siteId,id);
 }
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<EquipmentCommunicationSettings> create(@PathVariable long siteId,
                                                            @RequestBody EquipmentCommunicationSettings settings){
  return service.create(siteId,settings);
 }
 @PutMapping("/{id}")
 ConfigurationRecord<EquipmentCommunicationSettings> update(@PathVariable long siteId,@PathVariable long id,
                                                            @RequestParam long expectedVersion,
                                                            @RequestBody EquipmentCommunicationSettings settings){
  return service.update(siteId,id,expectedVersion,settings);
 }
 @DeleteMapping("/{id}")
 Map<String,Object> delete(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion){
  service.delete(siteId,id,expectedVersion);
  return Map.of("id",id,"removed",true);
 }
}
