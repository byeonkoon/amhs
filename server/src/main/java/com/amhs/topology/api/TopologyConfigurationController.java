package com.amhs.topology.api;
import com.amhs.platform.ConfigurationRecord;
import com.amhs.topology.EdgeSettings;
import com.amhs.topology.NodeSettings;
import com.amhs.topology.TopologyConfiguration;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** Track parameters. Administrator-only: the path is what makes it so, and the gate enforces it. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/topology/{revisionId}")
class TopologyConfigurationController {
    private final TopologyConfiguration topology;
    TopologyConfigurationController(TopologyConfiguration topology) { this.topology=topology; }

    @GetMapping("/nodes")
    List<ConfigurationRecord<NodeSettings>> nodes(@PathVariable long siteId,@PathVariable long revisionId) {
        return topology.nodes(siteId,revisionId);
    }
    @PutMapping("/nodes/{id}")
    ConfigurationRecord<NodeSettings> updateNode(@PathVariable long siteId,@PathVariable long revisionId,
            @PathVariable long id,@RequestParam long expectedVersion,@RequestBody NodeSettings settings) {
        return topology.updateNode(siteId,revisionId,id,expectedVersion,settings);
    }
    @GetMapping("/segments")
    List<ConfigurationRecord<EdgeSettings>> segments(@PathVariable long siteId,@PathVariable long revisionId) {
        return topology.edges(siteId,revisionId);
    }
    @PutMapping("/segments/{id}")
    ConfigurationRecord<EdgeSettings> updateSegment(@PathVariable long siteId,@PathVariable long revisionId,
            @PathVariable long id,@RequestParam long expectedVersion,@RequestBody EdgeSettings settings) {
        return topology.updateEdge(siteId,revisionId,id,expectedVersion,settings);
    }
}
