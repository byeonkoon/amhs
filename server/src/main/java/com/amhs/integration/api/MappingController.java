package com.amhs.integration.api;
import com.amhs.integration.MappingConfiguration;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.platform.Principal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.List;
import java.util.Map;

/**
 * The mapping documents a site has installed, and the ones shipped with the server.
 *
 * <p>Under {@code /configuration/}, so the access gate asks for an administrator and records who
 * changed what: installing a dialect decides how every command to those vehicles is written.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/mappings")
class MappingController {
 private final MappingConfiguration mappings; private final MappingLibrary library;
 MappingController(MappingConfiguration mappings,MappingLibrary library){ this.mappings=mappings; this.library=library; }

 @GetMapping
 List<MappingConfiguration.Installed> list(@PathVariable long siteId){ return mappings.list(siteId); }

 /** What is available to a protocol profile here: the site's own, and what the server ships. */
 @GetMapping("/available")
 Map<String,String> available(@PathVariable long siteId){ return library.inUse(siteId); }

 /** What is wrong with the shipped documents that are not in use, so a bad one is visible. */
 @GetMapping("/refused")
 Map<String,List<String>> refused(){ return library.refused(); }

 @GetMapping("/{code}")
 MappingConfiguration.Installed get(@PathVariable long siteId,@PathVariable String code){
  return mappings.get(siteId,code);
 }

 /** The document itself is the body; its own {@code mapping} name is what it is stored under. */
 @PostMapping(consumes="application/json")
 MappingConfiguration.Installed save(@PathVariable long siteId,@RequestBody String document){
  return mappings.save(siteId,document,actor());
 }

 @DeleteMapping("/{code}")
 Map<String,Object> remove(@PathVariable long siteId,@PathVariable String code){
  mappings.remove(siteId,code,actor());
  return Map.of("code",code,"removed",true);
 }

 /** Who the access gate let through; a request that never passed it is just "api". */
 private static String actor(){
  var attributes=RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(Principal.REQUEST_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  return principal instanceof Principal p?p.code():"api";
 }
}
