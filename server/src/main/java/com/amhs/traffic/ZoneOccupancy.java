package com.amhs.traffic;
import java.util.List;
/**
 * A zone and who currently counts against its capacity: vehicles standing in it and vehicles
 * already permitted to enter it. Leaving is never refused, so the list only shrinks by departure.
 */
public record ZoneOccupancy(long id, long revisionId, String code, int capacity, boolean enabled,
                            List<Long> nodeIds, List<Long> vehicleIds) {
    public ZoneOccupancy { nodeIds=List.copyOf(nodeIds); vehicleIds=List.copyOf(vehicleIds); }
    public boolean full() { return vehicleIds.size()>=capacity; }
}
