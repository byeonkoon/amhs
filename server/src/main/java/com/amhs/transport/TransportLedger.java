package com.amhs.transport;
import java.util.*;
/** Persistence port for the transport work ledger. */
public interface TransportLedger {
 long activeRevision(long siteId);
 /**
  * The map in use, or empty where the site has none yet. Asking is not requiring: a caller that
  * must carry on without a map cannot do it by catching the refusal, because the refusal has
  * already marked the transaction it was asked in for rollback.
  */
 OptionalLong activeRevisionIfAny(long siteId);
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
 /**
  * Takes the claim on these ports for the rest of the transaction, in id order. Every assignment
  * takes it for both its ports before looking at who uses them, so two cannot both see them free.
  */
 void lockPorts(List<Long> portIds);
 /**
  * Whether another order holding a vehicle is using one of these ports: as its source until it has
  * picked up, as its destination until it has finished. One transfer at a port at a time.
  */
 boolean portInUse(long siteId,long orderId,long sourcePortId,long destinationPortId);
 /**
  * Takes the claim on a carrier id for the rest of the transaction and says whether an order that
  * has not finished already carries it.
  */
 boolean carrierInTransport(long siteId,String carrierCode);
 /**
  * Waiting orders in the order dispatch takes them: higher priority first, then the one accepted
  * first, then the lower id.
  */
 List<TransportOrder> dispatchQueue(long siteId,int limit);
 /** The page of waiting orders that follows {@code after} in dispatch order; the first page when null. */
 List<TransportOrder> dispatchQueue(long siteId,TransportOrder after,int limit);
 /** Charge requests by vehicle: 1 asks to charge (work still wins), 2 must charge. */
 java.util.Map<Long,Integer> chargeLevels(long siteId);
 /** Sets a vehicle's charge request; 0 clears it. */
 void setChargeLevel(long siteId,long vehicleId,int level);
 /** How many orders for this kind of vehicle are waiting to be assigned. */
 int queuedCount(long siteId,com.amhs.platform.VehicleType type);
 /** A vehicle's open charge: when it was told to start, and when to stop (null until then). */
 record ChargeSession(java.time.OffsetDateTime startedAt,java.time.OffsetDateTime stopRequestedAt){}
 ChargeSession chargeSession(long siteId,long vehicleId);
 void openChargeSession(long siteId,long vehicleId);
 void requestChargeStop(long siteId,long vehicleId);
 void closeChargeSession(long siteId,long vehicleId);
 /** Vehicles a charge owns now: told to start and not yet seen to have stopped. */
 java.util.Set<Long> chargingVehicleIds(long siteId);
 /** Takes the claim on a place (a charge point) for the rest of the transaction. */
 void lockPlace(long nodeId);
 /** When the vehicle last finished work or a move, whichever is later; null when it never had either. */
 java.time.OffsetDateTime lastActivityEnded(long siteId,long vehicleId);
 /** Open moves out of zones at this site, one per vehicle at most. */
 List<Relocation> openRelocations(long siteId);
 /** Vehicles currently being moved out of a zone, which are not offered to dispatch meanwhile. */
 List<Long> relocatingVehicleIds(long siteId);
 /** Opens a move out of a zone. A second open one for the same vehicle is refused by the database. */
 Relocation startRelocation(long siteId,long revisionId,long vehicleId,Long zoneId,List<Long> routeNodeIds,long targetNodeId,
                            String purpose);
 /** Replaces the route of an open move, for a vehicle that is no longer on it. */
 void replaceRelocationRoute(long relocationId,List<Long> routeNodeIds);
 /**
  * Marks an open move as to be stopped and returns when that was first decided. Asking again keeps
  * the first time, so a report has to come after the decision to count as the vehicle's answer.
  */
 java.time.OffsetDateTime requestRelocationStop(long relocationId);
 /** Closes a move, saying why: arrived, or abandoned because it could no longer be carried out. */
 void finishRelocation(long relocationId,String reason);
 /** Orders that are still being worked on, newest first. Finished ones are history. */
 List<TransportOrder> listActive(long siteId,int limit);
 /**
  * Unfinished work counted over every order, not over a page of them: a count read off "the most
  * recent so many" is wrong exactly when the site is busiest.
  */
 record WorkCounts(int queued,int running,int recoveryRequired,java.time.OffsetDateTime oldestQueuedAt){}
 WorkCounts workCounts(long siteId);
 /** How many distinct carriers unfinished work is moving, over every order. */
 int carriersInTransport(long siteId);
 /** Every order in one status, oldest first; for statuses that each need someone's attention. */
 List<TransportOrder> inStatus(long siteId,OrderStatus status);
 /** Orders that have finished (completed, cancelled or failed), newest first. */
 List<TransportOrder> listFinished(long siteId,int limit);

 /**
  * Orders that ended at or after {@code since}, however many that is.
  *
  * <p>Counting over a window needs every order in it, not the most recent few: a count taken from
  * a capped list is right only while the cap is never reached, and wrong without saying so exactly
  * when the site is busiest.
  */
 List<TransportOrder> finishedSince(long siteId,java.time.OffsetDateTime since);
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

 /**
  * A transfer report the order's stage no longer allows -- late, or after a cancel was asked for.
  * The status stays; the report is kept as an event. An unfinished order takes the carrier location
  * it establishes, so a later cancel confirmation decides on where the carrier really is. A finished
  * order is not reopened: a location that differs from what it records is kept as late evidence for
  * a person, never dropped.
  */
 TransportOrder recordLateReport(long siteId,long orderId,OrderStatus reported,TransitionEvidence evidence,
                                 String actorCode,String traceId);
 /**
  * {@code kind}: AFTER_END, a report about an order already finished; CONFLICT, reports about an
  * unfinished order that contradict each other where nobody can tell which came first.
  */
 record LateEvidence(long id,long orderId,String kind,String orderStatus,String reportedStatus,String recordedLocation,
                     String reportedLocation,String evidenceKind,String commandId,java.time.OffsetDateTime receivedAt,
                     java.time.OffsetDateTime resolvedAt,String resolvedBy,String resolution){}
 /** Whether a report with this key was already handled for the order. */
 boolean transferReportSeen(long orderId,String reportKey);
 /** The action a device command was first reported for on this order, or null when it was never seen. */
 TransferAction actionOfCommand(long orderId,String commandId);
 /** Keeps a transfer report as it was judged. */
 void recordTransferReport(long orderId,String commandId,TransferAction action,OrderStatus reported,
                           CarrierLocation location,String outcome,String reportKey);
 /** Late evidence nobody has dealt with yet, oldest first. */
 List<LateEvidence> openLateEvidence(long siteId);
 /** A person has dealt with it and says how. False when it was not open. */
 boolean resolveLateEvidence(long siteId,long id,String resolution,String actorCode);
}
