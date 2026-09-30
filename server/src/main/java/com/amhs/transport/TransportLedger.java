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
 long assign(long orderId,long vehicleId);
 Optional<AssignmentView> activeAssignment(long siteId,long orderId);
 /** Vehicles currently held by some order at this site. */
 List<Long> assignedVehicleIds(long siteId);
 /** Applies a checked transition and appends the matching event in one transaction. */
 TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId);
}
