package com.amhs.transport;
import java.util.*;
/** Persistence port for the transport work ledger. */
public interface TransportLedger {
 long activeRevision(long siteId);
 Optional<PortAccess> findPort(long siteId,long revisionId,String portCode);
 Optional<TransportOrder> findByIdempotency(long siteId,String requestSource,String idempotencyKey);
 String fingerprintOf(long orderId);
 Optional<TransportOrder> find(long siteId,long orderId);
 List<TransportOrder> list(long siteId,OrderStatus status,int limit);
 List<OrderEventView> events(long siteId,long orderId);
 /** Inserts the order, its first event and any outbound command in one transaction. */
 TransportOrder create(TransportOrder draft,String fingerprint,String actorCode,String traceId);
 /** Reads the order under a row lock so the caller can decide and write without a gap. */
 Optional<TransportOrder> lock(long siteId,long orderId);
 /** Records an active assignment. A second active one for the same vehicle or order is rejected. */
 long assign(long orderId,long vehicleId,java.util.List<Long> routeNodeIds);
 /** The route this order was assigned against, empty when it has none. */
 List<Long> plannedRoute(long orderId);
 Optional<AssignmentView> activeAssignment(long siteId,long orderId);
 /** Every vehicle currently held at this site, with the order holding it. */
 List<AssignmentView> activeAssignments(long siteId);
 /** Vehicles currently held by some order at this site. */
 List<Long> assignedVehicleIds(long siteId);
 /** Orders that currently hold a vehicle at this site. */
 List<Long> assignedOrderIds(long siteId);
 /** The vehicle of the order's latest assignment, released or not; null when it never had one. */
 Long assignedVehicleOf(long orderId);
 /** Applies a checked transition and appends the matching event in one transaction. */
 TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId);
}
