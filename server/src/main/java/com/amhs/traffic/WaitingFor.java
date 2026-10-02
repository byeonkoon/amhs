package com.amhs.traffic;
import java.time.OffsetDateTime;
/**
 * A vehicle refused a node that another vehicle holds or stands on, and since when it has been
 * asking for that node. Worked out again on every read, so a blocker that has moved on is not named.
 */
public record WaitingFor(long vehicleId, long wantedNodeId, long blockerId, OffsetDateTime since) {}
