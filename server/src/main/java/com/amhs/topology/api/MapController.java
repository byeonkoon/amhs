package com.amhs.topology.api;
import com.amhs.topology.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/maps")
class MapController {
    private final TopologyQuery query;
    MapController(TopologyQuery query) { this.query=query; }
    @GetMapping("/{revisionId}")
    MapSnapshot map(@PathVariable long siteId, @PathVariable long revisionId) { return query.getMap(siteId,revisionId); }
}
