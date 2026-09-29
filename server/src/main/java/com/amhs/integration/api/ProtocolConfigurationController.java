package com.amhs.integration.api;
import com.amhs.integration.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/protocols")
class ProtocolConfigurationController {
 private final ProtocolConfiguration service;
 ProtocolConfigurationController(ProtocolConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<ProtocolSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<ProtocolSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<ProtocolSettings> create(@PathVariable long siteId,@RequestBody ProtocolSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<ProtocolSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody ProtocolSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
