package com.amhs.fleet;
import java.util.List;
import java.util.Optional;
/** Last reported vehicle state. Configuration says what exists; this says what is true now. */
public interface FleetState {
    List<VehicleSnapshot> snapshot(long siteId, long revisionId);
    Optional<VehicleSnapshot> find(long siteId, long revisionId, long vehicleId);
    VehicleSnapshot report(long siteId, long revisionId, long vehicleId, VehicleReport report);
    /**
     * A vehicle says where it is between nodes. Its node, and so what traffic counts it as standing
     * on, does not change, and neither do its mode and status: those come with a full report, which
     * also clears the coordinates again.
     */
    VehicleSnapshot reportBetweenNodes(long siteId, long revisionId, long vehicleId, double xMm, double yMm);
    /**
     * Mode and status only, in one statement on the vehicle's row: where it is (node or between
     * nodes) and what it carries stay exactly as stored. Between two nodes a vehicle given back to
     * remote control as idle is still on its way, so it is stored as moving.
     */
    VehicleSnapshot reportModeAndStatus(long siteId, long revisionId, long vehicleId, String mode, String status);
    /** A vehicle driving between nodes: where it is, and that it is moving, in one report. */
    VehicleSnapshot reportDriving(long siteId, long revisionId, long vehicleId, double xMm, double yMm);
    /** Locks the given vehicles in ascending id order so two assignments cannot deadlock. */
    void lockForAssignment(long siteId, List<Long> vehicleIds);
}
