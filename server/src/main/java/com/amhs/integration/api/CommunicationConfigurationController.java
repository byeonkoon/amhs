package com.amhs.integration.api;
import com.amhs.integration.*;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/communications")
class CommunicationConfigurationController {
 private final CommunicationConfiguration service;
 CommunicationConfigurationController(CommunicationConfiguration service){this.service=service;}
 @GetMapping List<ConfigurationRecord<CommunicationSettings>> list(@PathVariable long siteId){return service.list(siteId);}
 @GetMapping("/{id}") ConfigurationRecord<CommunicationSettings> get(@PathVariable long siteId,@PathVariable long id){return service.get(siteId,id);}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 ConfigurationRecord<CommunicationSettings> create(@PathVariable long siteId,@RequestBody CommunicationSettings settings){return service.create(siteId,settings);}
 @PutMapping("/{id}")
 ConfigurationRecord<CommunicationSettings> update(@PathVariable long siteId,@PathVariable long id,@RequestParam long expectedVersion,@RequestBody CommunicationSettings settings){return service.update(siteId,id,expectedVersion,settings);}
}
