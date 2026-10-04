package com.amhs.topology.api;
import com.amhs.topology.*;
import org.springframework.web.bind.annotation.*;
@RestController
class MapController {
    private final TopologyQuery query; private final TopologyConfiguration topology;
    MapController(TopologyQuery query,TopologyConfiguration topology) { this.query=query; this.topology=topology; }
    @GetMapping("/api/v1/sites/{siteId}/maps/{revisionId}")
    MapSnapshot map(@PathVariable long siteId, @PathVariable long revisionId) { return query.getMap(siteId,revisionId); }
    /**
     * Every revision this site has and which one it is running on.
     *
     * <p>A read, and open like the rest of them: a screen that does not know which map is in use
     * has to guess, and the guess is what puts a vehicle on the wrong track in somebody's eyes.
     */
    @GetMapping("/api/v1/sites/{siteId}/map-revisions")
    java.util.List<TopologyConfiguration.RevisionView> revisions(@PathVariable long siteId) {
        return topology.revisions(siteId);
    }
}
