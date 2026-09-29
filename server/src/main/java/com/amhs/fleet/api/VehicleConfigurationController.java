package com.amhs.fleet.api;
import com.amhs.fleet.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/vehicles")
class VehicleConfigurationController {
 private final VehicleConfiguration service;
 VehicleConfigurationController(VehicleConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<VehicleSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<VehicleSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<VehicleSettings> create(@PathVariable long siteId,@RequestBody VehicleSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<VehicleSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody VehicleSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
