package com.amhs.transport;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A move that belongs to no transport order: taking a vehicle out of a zone after its work there
 * is done. Open until the vehicle stands on {@code targetNodeId}.
 */
public record Relocation(long id,long siteId,long revisionId,long vehicleId,long zoneId,
                         List<Long> routeNodeIds,long targetNodeId,OffsetDateTime startedAt){
 public Relocation { routeNodeIds=List.copyOf(routeNodeIds); }
}
