package com.amhs.traffic;
import java.time.OffsetDateTime;
/** One node a vehicle is permitted to occupy. */
public record NodeHold(long id, long siteId, long revisionId, long nodeId, long vehicleId,
                       Long orderId, int sequence, OffsetDateTime grantedAt) {}
