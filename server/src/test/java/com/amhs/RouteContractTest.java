package com.amhs;
import com.amhs.routing.RouteResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.amhs.routing.RouteResult.Reason.*;
import static com.amhs.routing.RouteResult.Status.*;

/**
 * A11 reservations and A21 movement units are built straight from these lists, so a result
 * that contradicts itself must not exist even if no planner produces one today.
 */
class RouteContractTest {
 @Test void foundNeedsOneEdgeBetweenEachNodePair() {
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,null,List.of(1L,2L),List.of(),10d))
   .hasMessage("FOUND_NEEDS_ONE_EDGE_BETWEEN_EACH_NODE_PAIR");
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,null,List.of(1L),List.of(),10d))
   .hasMessage("FOUND_NEEDS_ONE_EDGE_BETWEEN_EACH_NODE_PAIR");
  assertThat(new RouteResult(1,1,FOUND,null,List.of(1L,2L),List.of(9L),10d).reachable()).isTrue();
 }
 @Test void reasonAndCostBelongToOppositeOutcomes() {
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,NO_PATH,List.of(1L,2L),List.of(9L),10d))
   .hasMessage("REASON_ONLY_WHEN_UNREACHABLE");
  assertThatThrownBy(()->new RouteResult(1,1,UNREACHABLE,null,List.of(),List.of(),null))
   .hasMessage("REASON_ONLY_WHEN_UNREACHABLE");
  assertThatThrownBy(()->new RouteResult(1,1,UNREACHABLE,NO_PATH,List.of(),List.of(),5d))
   .hasMessage("COST_ONLY_WHEN_REACHABLE");
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,null,List.of(1L,2L),List.of(9L),null))
   .hasMessage("COST_ONLY_WHEN_REACHABLE");
 }
 @Test void unreachableCarriesNoPathAndSameNodeCostsNothing() {
  assertThatThrownBy(()->new RouteResult(1,1,UNREACHABLE,START_UNKNOWN,List.of(1L),List.of(),null))
   .hasMessage("UNREACHABLE_CARRIES_NO_PATH");
  assertThatThrownBy(()->new RouteResult(1,1,SAME_NODE,null,List.of(1L),List.of(),4d))
   .hasMessage("SAME_NODE_IS_ONE_NODE_AT_ZERO_COST");
  assertThatThrownBy(()->new RouteResult(1,1,SAME_NODE,null,List.of(1L,2L),List.of(9L),0d))
   .hasMessage("SAME_NODE_IS_ONE_NODE_AT_ZERO_COST");
 }
 @Test void negativeAndNonFiniteCostsAreRejected() {
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,null,List.of(1L,2L),List.of(9L),-1d))
   .hasMessage("INVALID_ROUTE_COST");
  assertThatThrownBy(()->new RouteResult(1,1,FOUND,null,List.of(1L,2L),List.of(9L),Double.NaN))
   .hasMessage("INVALID_ROUTE_COST");
 }
}
