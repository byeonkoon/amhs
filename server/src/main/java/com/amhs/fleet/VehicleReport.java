package com.amhs.fleet;
/**
 * One state report about a vehicle. Null nodeId means the position is unknown, not unchanged.
 * {@code destinationChangeEnabled} is the vehicle saying it can take a new destination now; null
 * means it did not say, which a site that waits for the signal treats as no.
 */
public record VehicleReport(Long nodeId, boolean online, boolean remote, boolean idle,
                            boolean carrierPresent, String source, Boolean destinationChangeEnabled) {
    public VehicleReport(Long nodeId,boolean online,boolean remote,boolean idle,boolean carrierPresent,String source){
        this(nodeId,online,remote,idle,carrierPresent,source,null);
    }
}
