package com.amhs.fleet;
import java.util.List;
import java.util.Optional;
/** Last reported vehicle state. Configuration says what exists; this says what is true now. */
public interface FleetState {
    List<VehicleSnapshot> snapshot(long siteId, long revisionId);
    Optional<VehicleSnapshot> find(long siteId, long revisionId, long vehicleId);
    VehicleSnapshot report(long siteId, long revisionId, long vehicleId, VehicleReport report);
    /** Locks the given vehicles in ascending id order so two assignments cannot deadlock. */
    void lockForAssignment(long siteId, List<Long> vehicleIds);
}
