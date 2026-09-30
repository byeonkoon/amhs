package com.amhs.routing.api;
import com.amhs.routing.*;
import com.amhs.topology.TopologyQuery;
import org.springframework.web.bind.annotation.*;
/** Read-only static proposals. Live traffic costs belong to the future execution loop. */
@RestController
class RouteController {
    private final TopologyQuery topology;
    RouteController(TopologyQuery topology) { this.topology=topology; }
    @GetMapping("/api/v1/sites/{siteId}/maps/{revisionId}/route")
    RouteResult route(@PathVariable long siteId,@PathVariable long revisionId,
                      @RequestParam(required=false) Long startNodeId,@RequestParam(required=false) Long goalNodeId) {
        return new RoutePlanner().find(new RouteQuery(topology.getMap(siteId,revisionId),startNodeId,goalNodeId,EdgeCostPolicy.staticCost()));
    }
}
