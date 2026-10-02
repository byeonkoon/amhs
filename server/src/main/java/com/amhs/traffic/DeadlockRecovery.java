package com.amhs.traffic;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One vehicle giving way to break a deadlock.
 *
 * <p>STOPPING: told to stop, not yet seen standing still after the stop settled; it keeps its road.
 * YIELDING: its road ahead was given back and it is granted nothing new until the vehicle it gave way
 * to has taken the node it waited for. DONE or ABANDONED: over; the vehicle asks for road as usual.
 */
public record DeadlockRecovery(long id, long siteId, long vehicleId, long beneficiaryVehicleId, long wantedNodeId,
                               List<Long> chainVehicleIds, String status, String reason, String requestedBy,
                               OffsetDateTime requestedAt, String stopCommandId, OffsetDateTime stopSettledAt,
                               List<Long> releasedNodeIds, OffsetDateTime releasedAt, OffsetDateTime finishedAt,
                               String finishReason) {
    public DeadlockRecovery {
        chainVehicleIds=List.copyOf(chainVehicleIds);
        releasedNodeIds=releasedNodeIds==null?List.of():List.copyOf(releasedNodeIds);
    }
    public static final String STOPPING="STOPPING", YIELDING="YIELDING", DONE="DONE", ABANDONED="ABANDONED";
    public boolean open() { return finishedAt==null; }
}
