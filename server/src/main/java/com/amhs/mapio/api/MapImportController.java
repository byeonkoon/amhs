package com.amhs.mapio.api;
import com.amhs.mapio.MapDocument;
import com.amhs.mapio.MapImport;
import com.amhs.topology.TopologyConfiguration;
import org.springframework.web.bind.annotation.*;
/**
 * A whole map in and out as one document. Administrator-only: the path is what makes it so.
 *
 * <p>Importing writes a new draft revision and changes nothing the site is running on, so it does
 * not need the site stopped. Putting the result into use is the separate decision next door, which
 * does.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/topology")
class MapImportController {
 private final MapImport maps;
 MapImportController(MapImport maps){ this.maps=maps; }

 @PostMapping("/import")
 TopologyConfiguration.RevisionView importMap(@PathVariable long siteId,@RequestBody MapDocument document){
  return maps.importDocument(siteId,document);
 }
 @GetMapping("/{revisionId}/export")
 MapDocument export(@PathVariable long siteId,@PathVariable long revisionId){
  return maps.export(siteId,revisionId);
 }
}
