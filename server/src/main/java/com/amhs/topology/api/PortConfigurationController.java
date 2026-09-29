package com.amhs.topology.api;
import com.amhs.topology.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/ports")
class PortConfigurationController {
 private final PortConfiguration service;
 PortConfigurationController(PortConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<PortSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<PortSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<PortSettings> create(@PathVariable long siteId,@RequestBody PortSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<PortSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody PortSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
