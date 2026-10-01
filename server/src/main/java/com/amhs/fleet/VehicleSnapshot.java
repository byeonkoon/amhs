package com.amhs.fleet;
import com.amhs.platform.VehicleType;
/**
 * A vehicle as last reported, with the home node it was configured with (null when none).
 * {@code mode} (MANUAL, SEMI_AUTO, REMOTE) and {@code status} (IDLE, MOVING, ERROR, PAUSED,
 * CHARGING, LOADING, UNLOADING, RESET) are null until something reports. {@code nodeId} is null when nobody has said where it is, which is
 * different from being somewhere unreachable and must not be replaced by its configured node.
 */
public record VehicleSnapshot(long id, String code, VehicleType vType, Long nodeId,
                              boolean enabled, boolean assignmentEnabled,
                              boolean online, boolean remote, boolean idle, boolean carrierPresent,
                              String source, java.time.OffsetDateTime reportedAt,
                              Boolean destinationChangeEnabled, Long homeNodeId,
                              String mode, String status) {
    /**
     * Whether the last report came at or after {@code cutoff}. Configuration alone is never a
     * report: a vehicle nobody has heard from is not somewhere just because it was set up there.
     */
    /** The status as reported, or derived from the idle flag for a report that did not carry one. */
    public String effectiveStatus() {
        return status != null ? status : idle ? "IDLE" : "MOVING";
    }
    public boolean reportedSince(java.time.OffsetDateTime cutoff) {
        return reportedAt != null && !"INITIAL_CONFIGURATION".equals(source) && !reportedAt.isBefore(cutoff);
    }
}
