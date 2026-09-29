package com.amhs.integration.api;
import com.amhs.integration.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/connections")
class ConnectionConfigurationController {
 private final ConnectionConfiguration service;
 ConnectionConfigurationController(ConnectionConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<ConnectionSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<ConnectionSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<ConnectionSettings> create(@PathVariable long siteId,@RequestBody ConnectionSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<ConnectionSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody ConnectionSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
