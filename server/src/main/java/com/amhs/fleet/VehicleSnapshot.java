package com.amhs.fleet;
import com.amhs.platform.VehicleType;
/**
 * A vehicle as last reported. {@code nodeId} is null when nobody has said where it is, which is
 * different from being somewhere unreachable and must not be replaced by its configured node.
 */
public record VehicleSnapshot(long id, String code, VehicleType vType, Long nodeId,
                              boolean enabled, boolean assignmentEnabled,
                              boolean online, boolean remote, boolean idle, boolean carrierPresent,
                              String source, java.time.OffsetDateTime reportedAt) {}
