package com.amhs.integration.api;
import com.amhs.integration.HostEndpointConfiguration;
import com.amhs.integration.HostEndpointSettings;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Where this site's transport notices go, and what became of the last of them. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/host")
class HostEndpointController {
 private final HostEndpointConfiguration hosts;
 HostEndpointController(HostEndpointConfiguration hosts){ this.hosts=hosts; }

 /** The settings without the credential; {@code configured} says whether there are any at all. */
 @GetMapping
 Map<String,Object> get(@PathVariable long siteId){
  var found=hosts.get(siteId);
  return found.<Map<String,Object>>map(r->Map.of("configured",true,"endpoint",r))
   .orElse(Map.of("configured",false));
 }
 @PutMapping
 ConfigurationRecord<HostEndpointSettings> save(@PathVariable long siteId,@RequestBody HostEndpointSettings settings){
  return hosts.save(siteId,settings);
 }
 /** What has been said lately and what became of it. */
 @GetMapping("/notices")
 List<HostEndpointConfiguration.Notice> notices(@PathVariable long siteId,
                                                @RequestParam(defaultValue="50") int limit){
  return hosts.recent(siteId,limit);
 }
}
