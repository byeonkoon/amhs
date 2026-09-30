package com.amhs.integration.api;
import com.amhs.integration.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/vda-profiles")
class VdaProfileConfigurationController {
 private final VdaProfileConfiguration service;
 VdaProfileConfigurationController(VdaProfileConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<VdaProfileSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<VdaProfileSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<VdaProfileSettings> create(@PathVariable long siteId,@RequestBody VdaProfileSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<VdaProfileSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody VdaProfileSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
