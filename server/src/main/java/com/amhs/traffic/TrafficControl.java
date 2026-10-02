package com.amhs.traffic;
import java.util.List;
import java.util.Optional;

/**
 * Permission to occupy nodes.
 *
 * <p>A route says where a vehicle could go; this says where it may be. Nodes are the reserved
 * resource and an edge needs no separate reservation: a vehicle may only traverse an edge whose
 * endpoints it holds, so two vehicles can never take the same edge in opposite directions.
 *
 * <p>Locks are taken in one global order across the system — order row, then vehicle rows by
 * ascending id, then node rows by ascending id — so two decisions that overlap cannot each end
 * up holding half of what the other needs.
 */
public interface TrafficControl {
    /**
     * Grants the longest contiguous run of {@code routeNodeIds} starting at the vehicle's current
     * node, capped by the site's movement-authority node count.
     *
     * <p>The run stops at the first node it cannot have and never resumes past it. Skipping a
     * blocked node would hand the vehicle permission for somewhere it cannot legally reach, which
     * reads to the device as a clear path straight through whatever is in the way.
     *
     * <p>No node is granted that another vehicle reports standing on, held or not
     * ({@code NODE_OCCUPIED_BY_OTHER_VEHICLE}): where a vehicle is outranks what it was allowed.
     * Beyond the node it stands on, a node cannot be had when another vehicle holds it, when it
     * is out of service, when it is the first node of a zone that is already full
     * ({@code ZONE_FULL}), or, while the controller decides merges, when it is a merge point another
     * vehicle asked for earlier and is still asking for ({@code MERGE_EARLIER_REQUEST}). A stop node
     * is granted and then ends the run ({@code STOP_NODE}): the vehicle halts there first.
     *
     * @param routeNodeIds the remaining route, starting with the node the vehicle occupies now
     */
    ReservationGrant request(long siteId, long revisionId, long vehicleId, Long orderId, List<Long> routeNodeIds);

    /**
     * Records that the vehicle is at {@code nodeId} and releases what it holds behind that point.
     *
     * <p>This is the only thing that frees a node. Not a timer, not a sent command, not a lost
     * connection: only evidence that the vehicle is somewhere further along.
     */
    Progress advanced(long siteId, long vehicleId, long nodeId);

    /**
     * Releases everything the vehicle holds ahead of {@code positionNodeId}, keeping the node it
     * stands on. A null position releases nothing, because a vehicle nobody can locate may be
     * anywhere inside the run it was given.
     */
    int releaseAhead(long siteId, long vehicleId, Long positionNodeId, String reason);

    List<NodeHold> held(long siteId);
    /** Every zone at this site with the vehicles counting against its capacity. */
    List<ZoneOccupancy> zones(long siteId);
    List<NodeHold> heldBy(long siteId, long vehicleId);
    /** The chain of waits starting at this vehicle, if it closes back on itself. */
    Optional<Deadlock> deadlock(long siteId, long vehicleId);
    /** Every distinct closed chain at this site. */
    List<Deadlock> deadlocks(long siteId);
    /** Every vehicle at the site waiting for a node another vehicle holds or stands on now. */
    List<WaitingFor> waitingFor(long siteId);
}
