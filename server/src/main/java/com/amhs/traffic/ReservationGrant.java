package com.amhs.traffic;
import java.util.List;
/**
 * What a vehicle may occupy right now, as a contiguous run starting where it stands.
 *
 * <p>{@code complete} is false when the run stops short of the route that was asked for, and the
 * blocked fields say where and because of whom. A short grant is not a failure: the vehicle moves
 * as far as it is allowed and asks again.
 */
public record ReservationGrant(long vehicleId, List<Long> nodeIds, boolean complete,
                               Long blockedNodeId, Long blockedByVehicleId, String reason) {
    public ReservationGrant { nodeIds=List.copyOf(nodeIds); }
    /** Nothing was granted at all, so the vehicle cannot move even one node. */
    public boolean stalled() { return nodeIds.isEmpty(); }
}
