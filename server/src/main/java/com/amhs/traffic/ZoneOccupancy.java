package com.amhs.traffic;
import java.util.List;
/**
 * A zone and who currently counts against its capacity: vehicles standing in it and vehicles
 * already permitted to enter it. Leaving is never refused, so the list only shrinks by departure.
 */
public record ZoneOccupancy(long id, long revisionId, String code, int capacity, boolean enabled,
                            String kind, List<Long> nodeIds, List<Long> vehicleIds) {
    public ZoneOccupancy { nodeIds=List.copyOf(nodeIds); vehicleIds=List.copyOf(vehicleIds); }
    public boolean full() { return vehicleIds.size()>=capacity; }
    /**
     * Whether this is somewhere a vehicle is meant to stay.
     *
     * <p>The two kinds are the same shape and the opposite intent. A traffic zone is somewhere a
     * vehicle passes through and is moved out of once its work there is done; a maintenance area is
     * where a vehicle is put because it should not be on the line, and moving it out would undo the
     * only thing anyone wanted.
     */
    public boolean forMaintenance() { return "MAINTENANCE".equals(kind); }
}
