package com.amhs.traffic;
import java.util.List;

/**
 * A deadlock as an operator needs to see it: each link of the chain (who waits for which node, and
 * who is in the way there), and whether that link can be undone by the blocker giving back road it
 * holds ahead of where it stands. A link whose node someone is standing on cannot: only moving a
 * vehicle frees it, and that is a person's decision on the floor.
 *
 * @param recoveryId the recovery already under way for a vehicle of this chain, if any
 */
public record DeadlockPlan(List<Long> vehicleIds, List<Long> wantedNodeIds, List<Link> links, Long recoveryId) {
    public DeadlockPlan { vehicleIds=List.copyOf(vehicleIds); wantedNodeIds=List.copyOf(wantedNodeIds); links=List.copyOf(links); }

    /**
     * {@code vehicleId} waits for {@code wantedNodeId}; {@code blockerId} holds it or stands on it.
     * {@code releasable}: the blocker only holds it, ahead of the node it stands on, and nobody stands
     * on it, so the blocker giving way frees it.
     */
    public record Link(long vehicleId, long wantedNodeId, long blockerId, boolean blockerStandsThere, boolean releasable) {}

    /** Vehicles that could give way to break this chain. */
    public List<Long> candidates() {
        return links.stream().filter(Link::releasable).map(Link::blockerId).distinct().toList();
    }
}
