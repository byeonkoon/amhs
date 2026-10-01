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
 /**
  * Records an active assignment. A second active one for the same vehicle or order is rejected.
  * {@code pickupIndex} is where in the route the pickup is; nothing past it is authorised before
  * the pickup has been reported.
  */
 long assign(long orderId,long vehicleId,java.util.List<Long> routeNodeIds,int pickupIndex);
 /** Index of the pickup in the active route, or -1 when the assignment predates it (whole route). */
 int pickupIndex(long orderId);
 /** The route this order was assigned against, empty when it has none. */
 List<Long> plannedRoute(long orderId);
 /**
  * Index of the vehicle's position in the planned route. A route may pass the same node twice
  * (a lap round a full zone), so the position is found from here forward, never from the start.
  */
 int routeCursor(long orderId);
 /** Moves the cursor forward; never back. */
 void moveRouteCursor(long orderId,int cursor);
 /** Replaces what lies ahead in the route, keeping the cursor, with the pickup's new place in it. */
 void replaceRoute(long orderId,java.util.List<Long> routeNodeIds,int pickupIndex);

 Optional<AssignmentView> activeAssignment(long siteId,long orderId);
 /** Every vehicle currently held at this site, with the order holding it. */
 List<AssignmentView> activeAssignments(long siteId);
 /** Vehicles currently held by some order at this site. */
 List<Long> assignedVehicleIds(long siteId);
 /** Orders that currently hold a vehicle at this site. */
 List<Long> assignedOrderIds(long siteId);
 /** The vehicle of the order's latest assignment, released or not; null when it never had one. */
 Long assignedVehicleOf(long orderId);
 /** Open moves out of zones at this site, one per vehicle at most. */
 List<Relocation> openRelocations(long siteId);
 /** Vehicles currently being moved out of a zone, which are not offered to dispatch meanwhile. */
 List<Long> relocatingVehicleIds(long siteId);
 /** Opens a move out of a zone. A second open one for the same vehicle is refused by the database. */
 Relocation startRelocation(long siteId,long revisionId,long vehicleId,Long zoneId,List<Long> routeNodeIds,long targetNodeId,
                            String purpose);
 /** Replaces the route of an open move, for a vehicle that is no longer on it. */
 void replaceRelocationRoute(long relocationId,List<Long> routeNodeIds);
 /** Closes a move, saying why: arrived, or abandoned because it could no longer be carried out. */
 void finishRelocation(long relocationId,String reason);
 /** Orders that are still being worked on, newest first. Finished ones are history. */
 List<TransportOrder> listActive(long siteId,int limit);
 /** Orders that have finished (completed, cancelled or failed), newest first. */
 List<TransportOrder> listFinished(long siteId,int limit);
 /** Applies a checked transition resting on the system's own bookkeeping. */
 default TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId){
  return transition(siteId,orderId,target,reason,actorCode,traceId,TransitionEvidence.system());
 }
 /**
  * Applies a checked transition on the given evidence and appends the matching event, in one
  * transaction. Finishing a pickup or a drop, or trying one again, is refused unless the evidence
  * establishes where the carrier is; that location is written with the transition and kept with the
  * event. A claim only a person can make is refused without an identified person.
  */
 TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId,
                           TransitionEvidence evidence);
}
