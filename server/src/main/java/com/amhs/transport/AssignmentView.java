package com.amhs.transport;
import java.time.OffsetDateTime;
/** One vehicle held for one order. An active assignment has no releasedAt. */
public record AssignmentView(long id, long orderId, long vehicleId,
                             OffsetDateTime assignedAt, OffsetDateTime releasedAt, String releaseReason) {}
