package com.amhs.transport;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A move that belongs to no transport order: taking a vehicle out of a zone after its work there
 * is done, or an operator sending it to a node. Open until the vehicle stands on
 * {@code targetNodeId}. {@code zoneId} is null for an operator's move. {@code stopRequestedAt} is
 * when the controller decided the move must end early (the vehicle was taken out of use), null
 * while it is not being stopped.
 */
public record Relocation(long id,long siteId,long revisionId,long vehicleId,Long zoneId,
                         List<Long> routeNodeIds,long targetNodeId,OffsetDateTime startedAt,String purpose,
                         OffsetDateTime stopRequestedAt){
 /** Leaving a zone after the work there is done. */
 public static final String ZONE_EVACUATION="ZONE_EVACUATION";
 /** An operator sending the vehicle to a node. */
 public static final String MANUAL="MANUAL";
 public Relocation { routeNodeIds=List.copyOf(routeNodeIds); }
}
