package com.amhs;

import com.amhs.routing.*;
import com.amhs.topology.MapSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.amhs.routing.RouteResult.Status.*;
import static com.amhs.routing.RouteResult.Reason.*;

class RoutePlannerTest {
    private final RoutePlanner planner=new RoutePlanner();
    private MapSnapshot.Node node(long id,boolean enabled) { return new MapSnapshot.Node(id,"N"+id,id,0,enabled); }
    private MapSnapshot.Edge edge(long id,long from,long to,double cost) { return new MapSnapshot.Edge(id,from,to,cost,true); }
    private MapSnapshot map(List<MapSnapshot.Edge> edges) {
        return new MapSnapshot(1,2,List.of(node(1,true),node(2,true),node(3,true),node(4,true),node(5,true)),edges,List.of());
    }
    private RouteResult route(MapSnapshot map,Long from,Long to) {
        return planner.find(new RouteQuery(map,from,to,EdgeCostPolicy.staticCost()));
    }

    @Test void cheaperParallelEdgeAndOrderedSegmentsArePreserved() {
        var result=route(map(List.of(edge(9,1,2,8),edge(3,1,2,2),edge(4,2,4,3))),1L,4L);
        assertThat(result.status()).isEqualTo(FOUND);
        assertThat(result.nodeIds()).containsExactly(1L,2L,4L);
        assertThat(result.edgeIds()).containsExactly(3L,4L);
        assertThat(result.costSeconds()).isEqualTo(5);
    }

    @Test void equalCostsAreIndependentOfInputOrder() {
        var edges=new ArrayList<>(List.of(edge(8,1,2,1),edge(2,1,2,1),edge(9,1,3,1),edge(4,2,4,1),edge(5,3,4,1)));
        var expected=route(map(edges),1L,4L);
        assertThat(expected.edgeIds()).containsExactly(2L,4L);
        for(int seed=0;seed<20;seed++) {
            Collections.shuffle(edges,new Random(seed));
            assertThat(route(map(edges),1L,4L)).isEqualTo(expected);
        }
    }

    @Test void unknownPositionsHaveExplicitReasonsAndNoFallback() {
        var map=map(List.of(edge(1,1,2,1)));
        assertThat(route(map,null,2L).reason()).isEqualTo(START_UNKNOWN);
        assertThat(route(map,99L,2L).reason()).isEqualTo(START_UNKNOWN);
        assertThat(route(map,1L,null).reason()).isEqualTo(GOAL_UNKNOWN);
        assertThat(route(map,1L,99L).reason()).isEqualTo(GOAL_UNKNOWN);
    }

    @Test void disabledEndpointsAndTransitNodesAreHardBlocks() {
        var map=new MapSnapshot(1,2,List.of(node(1,true),node(2,false),node(3,true)),List.of(edge(1,1,2,1),edge(2,2,3,1)),List.of());
        assertThat(route(map,2L,3L).reason()).isEqualTo(START_BLOCKED);
        assertThat(route(map,1L,2L).reason()).isEqualTo(GOAL_BLOCKED);
        assertThat(route(map,1L,3L).reason()).isEqualTo(NO_PATH);
        assertThat(route(map,2L,2L).reason()).isEqualTo(START_BLOCKED);
    }

    @Test void sameNodeIsDistinctFromNoPathAndDisabledEdgesAreExcluded() {
        var map=map(List.of(new MapSnapshot.Edge(1,1,2,1,false)));
        var same=route(map,1L,1L);
        assertThat(same.status()).isEqualTo(SAME_NODE);
        assertThat(same.costSeconds()).isZero();
        assertThat(same.edgeIds()).isEmpty();
        var absent=route(map,1L,2L);
        assertThat(absent.reason()).isEqualTo(NO_PATH);
        assertThat(absent.costSeconds()).isNull();
        assertThat(absent.nodeIds()).isEmpty();
    }

    @Test void trafficPenaltyChoosesDetourButDoesNotForbidOnlyRoute() {
        var map=map(List.of(edge(1,1,2,1),edge(2,2,4,1),edge(3,1,3,3),edge(4,3,4,3)));
        var policy=EdgeCostPolicy.trafficAware(Map.of(),Map.of(2L,10d));
        assertThat(planner.find(new RouteQuery(map,1L,4L,policy)).nodeIds()).containsExactly(1L,3L,4L);
        var only=map(List.of(edge(1,1,2,1),edge(2,2,4,1)));
        assertThat(planner.find(new RouteQuery(only,1L,4L,policy)).costSeconds()).isEqualTo(12);
        assertThat(planner.find(new RouteQuery(only,2L,4L,policy)).costSeconds()).isEqualTo(1);
    }

    @Test void trafficLoadAndPenaltySnapshotsDoNotChangeAcrossCalls() throws Exception {
        var costs=new HashMap<Long,Double>(); costs.put(1L,4d);
        var penalties=new HashMap<Long,Double>(); penalties.put(2L,5d);
        var policy=EdgeCostPolicy.trafficAware(costs,penalties);
        costs.clear(); penalties.clear();
        var map=map(List.of(edge(1,1,2,2)));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var staticCall=pool.submit(()->route(map,1L,2L));
            var dynamicCall=pool.submit(()->planner.find(new RouteQuery(map,1L,2L,policy)));
            assertThat(staticCall.get(5,TimeUnit.SECONDS).costSeconds()).isEqualTo(2);
            assertThat(dynamicCall.get(5,TimeUnit.SECONDS).costSeconds()).isEqualTo(13);
        }
        assertThat(map.edges().getFirst().cost()).isEqualTo(2);
    }

    @Test void zeroCostCyclesTerminateWithAValidSimplePath() {
        var result=route(map(List.of(edge(1,1,2,0),edge(2,2,1,0),edge(3,2,3,0),edge(4,3,2,0),edge(5,3,4,1))),1L,4L);
        assertThat(result.nodeIds()).containsExactly(1L,2L,3L,4L);
        assertThat(result.costSeconds()).isEqualTo(1);
    }

    @Test void invalidCostsAndReferencesAreRejected() {
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(()->route(map(List.of(edge(1,1,2,value))),1L,2L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->planner.find(new RouteQuery(map(List.of(edge(1,1,2,1))),1L,2L,e->value))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->route(map(List.of(edge(1,1,99,1))),1L,2L)).hasMessage("INVALID_EDGE");
        assertThatThrownBy(()->route(map(List.of(edge(1,1,2,1),edge(1,2,3,1))),1L,2L)).hasMessage("INVALID_EDGE");
        assertThatThrownBy(()->EdgeCostPolicy.trafficAware(Map.of(1L,0.5),Map.of())).hasMessage("INVALID_LOAD_FACTOR");
        assertThatThrownBy(()->EdgeCostPolicy.trafficAware(Map.of(),Map.of(1L,-1d))).hasMessage("INVALID_NODE_PENALTY");
        assertThatThrownBy(()->route(map(List.of(edge(1,1,2,Double.MAX_VALUE),edge(2,2,3,Double.MAX_VALUE))),1L,3L)).hasMessage("ROUTE_COST_OVERFLOW");
    }

    @Test void generatedGraphsMatchIndependentAllPairsShortestPaths() {
        var random=new Random(901);
        for(int sample=0;sample<60;sample++) {
            var edges=new ArrayList<MapSnapshot.Edge>(); var distances=new double[5][5]; long id=1;
            for(int i=0;i<5;i++) { Arrays.fill(distances[i],Double.POSITIVE_INFINITY); distances[i][i]=0; }
            for(int i=0;i<5;i++) for(int j=0;j<5;j++) if(i!=j&&random.nextBoolean()) {
                double cost=random.nextInt(6); edges.add(edge(id++,i+1,j+1,cost)); distances[i][j]=cost;
            }
            for(int k=0;k<5;k++) for(int i=0;i<5;i++) for(int j=0;j<5;j++) distances[i][j]=Math.min(distances[i][j],distances[i][k]+distances[k][j]);
            var graph=map(edges);
            for(int i=0;i<5;i++) for(int j=0;j<5;j++) {
                var result=route(graph,(long)i+1,(long)j+1);
                if(Double.isInfinite(distances[i][j])) assertThat(result.reason()).isEqualTo(NO_PATH);
                else {
                    assertThat(result.costSeconds()).isEqualTo(distances[i][j]);
                    assertThat(result.edgeIds()).hasSize(result.nodeIds().size()-1);
                    double sum=0;
                    for(int n=0;n<result.edgeIds().size();n++) {
                        long segment=result.edgeIds().get(n);
                        var selected=edges.stream().filter(e->e.id()==segment).findFirst().orElseThrow();
                        assertThat(selected.fromNodeId()).isEqualTo(result.nodeIds().get(n));
                        assertThat(selected.toNodeId()).isEqualTo(result.nodeIds().get(n+1));
                        sum+=selected.cost();
                    }
                    assertThat(sum).isEqualTo(result.costSeconds());
                }
            }
        }
    }
}
