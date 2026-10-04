package com.amhs.platform.api;
import com.amhs.platform.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/system")
class OperationOptionController {
 private final OperationOptionConfiguration service;
 OperationOptionController(OperationOptionConfiguration service){this.service=service;}
 @GetMapping ConfigurationRecord<OperationOptions> get(@PathVariable long siteId){return service.get(siteId);}
 /** Whether the site is running or in maintenance. A read: every screen that offers a change needs it. */
 @GetMapping("/mode") java.util.Map<String,String> mode(@PathVariable long siteId){
  return java.util.Map.of("mode",service.mode(siteId));
 }
 /**
  * Puts the site into maintenance, or back to running. Administrator-only, and it is what makes
  * every other configuration change possible.
  */
 @PutMapping("/mode") java.util.Map<String,String> setMode(@PathVariable long siteId,
   @RequestBody java.util.Map<String,String> body){
  return java.util.Map.of("mode",service.setMode(siteId,body.get("mode")));
 }
 @PutMapping ConfigurationRecord<OperationOptions> update(@PathVariable long siteId,@RequestParam long expectedVersion,@RequestBody OperationOptions settings){return service.update(siteId,expectedVersion,settings);}
}
