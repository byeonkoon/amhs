package com.amhs.fleet;
import com.amhs.platform.VehicleType;
/**
 * A vehicle as last reported, with the home node it was configured with (null when none).
 * {@code mode} (MANUAL, SEMI_AUTO, REMOTE) and {@code status} (IDLE, MOVING, ERROR, PAUSED,
 * CHARGING, LOADING, UNLOADING, RESET) are null until something reports. {@code nodeId} is null when nobody has said where it is, which is
 * different from being somewhere unreachable and must not be replaced by its configured node.
 *
 * <p>{@code xMm}/{@code yMm} say where it is while it drives between nodes; both are null while it
 * stands on {@code nodeId}. The node stays the position that counts for traffic: it changes only
 * when the vehicle arrives at the next one. {@code homeNodeId} is where it waits, {@code
 * chargeNodeId} where it charges, {@code speedMmS} its configured travel speed. {@code batteryPct}
 * is its state of charge as last reported (null when it reports none); {@code chargingPolicy} is
 * DISABLED, HOME (its own charge node) or NEAREST (the nearest free charge point). {@code idleSince}
 * is when it last became idle on a node, null while it is not idle.
 */
public record VehicleSnapshot(long id, String code, VehicleType vType, Long nodeId,
                              boolean enabled, boolean assignmentEnabled,
                              boolean online, boolean remote, boolean idle, boolean carrierPresent,
                              String source, java.time.OffsetDateTime reportedAt,
                              Boolean destinationChangeEnabled, Long homeNodeId,
                              String mode, String status,
                              Double xMm, Double yMm, Long chargeNodeId, int speedMmS,
                              Double batteryPct, String chargingPolicy, java.time.OffsetDateTime idleSince,
                              Double socPeakPct, java.time.OffsetDateTime socPeakAt,
                              java.time.OffsetDateTime outOfServiceSince, String outOfServiceReason,
                              String outOfServiceBy) {
    /**
     * Whether somebody has said this vehicle is not to be worked.
     *
     * <p>Different from the configured "not for dispatch", which says how a vehicle is normally
     * used. This says something happened to this one, and it carries who said so and when.
     */
    public boolean outOfService() { return outOfServiceSince != null; }
    /**
     * Whether this vehicle carries its own charge, or is fed from the rail and carries a buffer.
     *
     * <p>The two look alike in a column of percentages and are not alike at all. A charge runs down
     * as the vehicle works and is put back on purpose; a buffer is kept full by the rail, and the
     * only thing that empties it is the vehicle not being fed.
     */
    public boolean carriesItsOwnCharge() {
        return chargingPolicy != null && !"DISABLED".equals(chargingPolicy);
    }

    /**
     * How far the state of charge has fallen since the current fall began.
     *
     * <p>{@code socPeakAt} is when it began: the last reading that was not lower than the one
     * before it, so a charge that is steady or rising keeps starting over. Null when there is
     * nothing to compare against. Falling is not a fault by itself -- a working vehicle's charge
     * falls all day -- so the caller decides with the site's window and drop; this only says what
     * the readings did.
     */
    public Double droppedSincePeak() {
        return batteryPct == null || socPeakPct == null ? null : socPeakPct - batteryPct;
    }
    /**
     * Whether the last report came at or after {@code cutoff}. Configuration alone is never a
     * report: a vehicle nobody has heard from is not somewhere just because it was set up there.
     */
    /** The status as reported, or derived from the idle flag for a report that did not carry one. */
    public String effectiveStatus() {
        return status != null ? status : idle ? "IDLE" : "MOVING";
    }
    /** Whether it says where it is between two nodes, rather than standing on {@code nodeId}. */
    public boolean betweenNodes() {
        return xMm != null || yMm != null;
    }
    public boolean reportedSince(java.time.OffsetDateTime cutoff) {
        return reportedAt != null && !"INITIAL_CONFIGURATION".equals(source) && !reportedAt.isBefore(cutoff);
    }
}
