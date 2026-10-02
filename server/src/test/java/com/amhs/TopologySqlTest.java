package com.amhs;

import com.amhs.platform.ConfigurationException;
import com.amhs.platform.VehicleType;
import com.amhs.routing.RoutePlanner;
import com.amhs.topology.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
    "amhs.configuration.writes-enabled=true"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TopologySqlTest {
    @Autowired TopologyQuery maps;
    @Autowired TopologyValidation validation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void draftHasSeparateNetworksAndPreservesActiveRevision() throws Exception {
        var draft = maps.getMap(1,2);
        assertThat(draft.nodes()).hasSize(12);
        assertThat(draft.nodes().stream().filter(n -> n.code().startsWith("OHT-"))).hasSize(6);
        assertThat(draft.nodes().stream().filter(n -> n.code().startsWith("AGV-"))).hasSize(6);
        assertThat(draft.ports()).hasSize(4);
        assertThat(draft.ports().stream().filter(p -> p.accessVehicleType()==VehicleType.OHT)).hasSize(2);
        assertThat(draft.ports().stream().filter(p -> p.accessVehicleType()==VehicleType.AGV)).hasSize(2);
        var types = new java.util.HashMap<Long,String>();
        draft.nodes().forEach(n -> types.put(n.id(),n.code().substring(0,3)));
        assertThat(draft.edges()).allSatisfy(e -> assertThat(types.get(e.fromNodeId())).isEqualTo(types.get(e.toNodeId())));
        // The active layout is the simulation one: a twenty-node OHT ring with a three-node bay
        // off it, and a twelve-node AGV loop that splits and rejoins, kept apart from the draft
        // revision above.
        var active=maps.getMap(1,1);
        assertThat(active.nodes()).hasSize(35);
        assertThat(active.nodes().stream().filter(n -> n.code().startsWith("OHT-"))).hasSize(23);
        assertThat(active.nodes().stream().filter(n -> n.code().startsWith("AGV-"))).hasSize(12);
        // The bay rejoins the ring at a merge point, and the AGV loop rejoins at another.
        assertThat(active.nodes().stream().filter(MapSnapshot.Node::mergePoint).map(MapSnapshot.Node::id))
            .containsExactlyInAnyOrder(110L,207L);
        // One-way: no span of the active layout carries an edge in both directions, which is what
        // keeps two vehicles from meeting nose to nose instead of queueing.
        var spans=active.edges().stream().map(e -> e.fromNodeId()+">"+e.toNodeId()).collect(java.util.stream.Collectors.toSet());
        assertThat(active.edges()).noneMatch(e -> spans.contains(e.toNodeId()+">"+e.fromNodeId()));
        assertThat(jdbc.queryForObject("SELECT id FROM topology.map_revision WHERE site_id=1 AND status='ACTIVE'",Long.class)).isEqualTo(1L);
        mvc.perform(get("/api/v1/sites/1/maps/2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revisionId").value(2))
            .andExpect(jsonPath("$.nodes.length()").value(12)).andExpect(jsonPath("$.ports.length()").value(4));
    }

    @Test void fixtureExercisesNormalBlockedDisconnectedAndWrongDirectionPaths() {
        var map=maps.getMap(1,2); var planner=new RoutePlanner();
        for (long first : new long[]{1101,1201}) {
            var route=planner.find(map,first,first+3);
            assertThat(route.nodeIds()).containsExactly(first,first+1,first+2,first+3);
            assertThat(route.costSeconds()).isEqualTo(9000);
            assertThat(planner.find(map,first+3,first).reachable()).isFalse();
            assertThat(planner.find(map,first,first+4).reachable()).isFalse();
            assertThat(planner.find(map,first,first+5).reachable()).isFalse();
            assertThat(map.edges()).noneMatch(e -> e.fromNodeId()==first+5 || e.toNodeId()==first+5);
        }
        assertThat(planner.find(map,1101,1201).reachable()).isFalse();
        assertThat(planner.find(map,1201,1101).reachable()).isFalse();
    }

    @Test void fleetReadsOnlyRequestedRevisionAndDefaultsToActive() throws Exception {
        mvc.perform(get("/api/v1/sites/1/vehicles"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].id").value(1)).andExpect(jsonPath("$[1].id").value(2))
            .andExpect(jsonPath("$[2].id").value(3)).andExpect(jsonPath("$[3].id").value(4));
        mvc.perform(get("/api/v1/sites/1/vehicles?revisionId=2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].vType").value("OHT"))
            .andExpect(jsonPath("$[1].vType").value("AGV"))
            .andExpect(jsonPath("$[0].currentNodeId").value(1101))
            .andExpect(jsonPath("$[1].currentNodeId").value(1201))
            .andExpect(jsonPath("$[0].assignmentEnabled").value(false))
            .andExpect(jsonPath("$[1].assignmentEnabled").value(false));
        jdbc.update("INSERT INTO topology.map_revision VALUES(999,1,999,'DRAFT')");
        mvc.perform(get("/api/v1/sites/1/vehicles?revisionId=999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void unknownAndCrossSiteRevisionsReturn404() throws Exception {
        jdbc.update("INSERT INTO platform.site VALUES(999,'EMPTY-TEST','Empty test site')");
        for (String path : new String[]{
                "/api/v1/sites/1/maps/999", "/api/v1/sites/999/maps/2",
                "/api/v1/sites/1/vehicles?revisionId=999", "/api/v1/sites/999/vehicles?revisionId=2",
                "/api/v1/sites/999/vehicles", "/api/v1/sites/998/vehicles"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test void portValidationRejectsOtherRevisionAndWrongNetworkIncludingBlockedEdge() {
        assertThatThrownBy(() -> validation.portNode(1,2,101,VehicleType.OHT,-1))
            .isInstanceOf(ConfigurationException.class).hasMessage("NODE_OUTSIDE_REVISION");
        assertThatThrownBy(() -> validation.portNode(1,2,1105,VehicleType.AGV,-1))
            .isInstanceOf(ConfigurationException.class).hasMessage("VEHICLE_TYPE_NETWORK_MISMATCH");
        validation.portNode(1,2,1105,VehicleType.OHT,-1);
    }

    @Test void databaseRejectsPortNodeFromOtherRevision() {
        assertThatThrownBy(() -> jdbc.update("UPDATE topology.port SET linked_node_id=101 WHERE id=1101"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsEdgeNodeFromOtherRevision() {
        assertThatThrownBy(() -> jdbc.update("UPDATE topology.edge SET to_node_id=101 WHERE id=1101"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsVehicleNodeFromOtherRevision() {
        assertThatThrownBy(() -> jdbc.update("UPDATE fleet.vehicle_profile SET initial_node_id=101 WHERE id=1101"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void routeApiReturnsScopedSegmentSequenceAndNoPathReasons() throws Exception {
        mvc.perform(get("/api/v1/sites/1/maps/2/route?startNodeId=1101&goalNodeId=1104"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FOUND"))
            .andExpect(jsonPath("$.edgeIds[0]").value(1101)).andExpect(jsonPath("$.edgeIds[2]").value(1103))
            .andExpect(jsonPath("$.costSeconds").value(9000));
        mvc.perform(get("/api/v1/sites/1/maps/2/route?startNodeId=1101&goalNodeId=1201"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("NO_PATH"));
        mvc.perform(get("/api/v1/sites/1/maps/2/route?goalNodeId=1104"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("START_UNKNOWN"));
        mvc.perform(get("/api/v1/sites/999/maps/2/route?startNodeId=1101&goalNodeId=1104"))
            .andExpect(status().isNotFound());
    }

    @Test void persistedDisabledNodeIsVisibleAndExcludedFromRoute() throws Exception {
        jdbc.update("UPDATE topology.node SET enabled=FALSE WHERE id=1102");
        assertThat(maps.getMap(1,2).nodes().stream().filter(n->n.id()==1102).findFirst().orElseThrow().enabled()).isFalse();
        mvc.perform(get("/api/v1/sites/1/maps/2/route?startNodeId=1101&goalNodeId=1104"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("NO_PATH"));
        mvc.perform(get("/api/v1/sites/1/maps/2/route?startNodeId=1102&goalNodeId=1104"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("START_BLOCKED"));
    }
}
