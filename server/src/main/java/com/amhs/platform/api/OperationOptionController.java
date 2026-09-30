package com.amhs.platform.api;
import com.amhs.platform.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/system")
class OperationOptionController {
 private final OperationOptionConfiguration service;
 OperationOptionController(OperationOptionConfiguration service){this.service=service;}
 @GetMapping ConfigurationRecord<OperationOptions> get(@PathVariable long siteId){return service.get(siteId);}
 @PutMapping ConfigurationRecord<OperationOptions> update(@PathVariable long siteId,@RequestParam long expectedVersion,@RequestBody OperationOptions settings){return service.update(siteId,expectedVersion,settings);}
}
