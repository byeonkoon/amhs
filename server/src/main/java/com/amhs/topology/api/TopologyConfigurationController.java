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

    /**
     * Puts this revision into use. Administrator-only, and the site has to be in maintenance like
     * every other track change -- swapping the ground under open work is the one change that
     * cannot be undone by editing it back.
     */
    @PostMapping("/activate")
    TopologyConfiguration.RevisionView activate(@PathVariable long siteId,@PathVariable long revisionId) {
        return topology.activate(siteId,revisionId);
    }
    @GetMapping("/nodes")
    List<ConfigurationRecord<NodeSettings>> nodes(@PathVariable long siteId,@PathVariable long revisionId) {
        return topology.nodes(siteId,revisionId);
    }
    @PutMapping("/nodes/{id}")
    ConfigurationRecord<NodeSettings> updateNode(@PathVariable long siteId,@PathVariable long revisionId,
            @PathVariable long id,@RequestParam long expectedVersion,@RequestBody NodeSettings settings) {
        return topology.updateNode(siteId,revisionId,id,expectedVersion,settings);
    }
    @PostMapping("/nodes")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ConfigurationRecord<NodeSettings> createNode(@PathVariable long siteId,@PathVariable long revisionId,
            @RequestBody NodeSettings settings) {
        return topology.createNode(siteId,revisionId,settings);
    }
    @DeleteMapping("/nodes/{id}")
    void deleteNode(@PathVariable long siteId,@PathVariable long revisionId,@PathVariable long id) {
        topology.deleteNode(siteId,revisionId,id);
    }
    @PostMapping("/segments")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ConfigurationRecord<EdgeSettings> createSegment(@PathVariable long siteId,@PathVariable long revisionId,
            @RequestBody EdgeSettings settings) {
        return topology.createEdge(siteId,revisionId,settings);
    }
    @DeleteMapping("/segments/{id}")
    void deleteSegment(@PathVariable long siteId,@PathVariable long revisionId,@PathVariable long id) {
        topology.deleteEdge(siteId,revisionId,id);
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
