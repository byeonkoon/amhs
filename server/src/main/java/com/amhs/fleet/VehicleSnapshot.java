package com.amhs.fleet;
import com.amhs.platform.VehicleType;
/**
 * A vehicle as last reported. {@code nodeId} is null when nobody has said where it is, which is
 * different from being somewhere unreachable and must not be replaced by its configured node.
 */
public record VehicleSnapshot(long id, String code, VehicleType vType, Long nodeId,
                              boolean enabled, boolean assignmentEnabled,
                              boolean online, boolean remote, boolean idle, boolean carrierPresent,
                              String source, java.time.OffsetDateTime reportedAt,
                              Boolean destinationChangeEnabled) {
    /**
     * Whether the last report came at or after {@code cutoff}. Configuration alone is never a
     * report: a vehicle nobody has heard from is not somewhere just because it was set up there.
     */
    public boolean reportedSince(java.time.OffsetDateTime cutoff) {
        return reportedAt != null && !"INITIAL_CONFIGURATION".equals(source) && !reportedAt.isBefore(cutoff);
    }
}
