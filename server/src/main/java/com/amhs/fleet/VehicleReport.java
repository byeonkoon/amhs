package com.amhs.fleet;
import java.util.Set;
/**
 * One state report about a vehicle. Null nodeId means the position is unknown, not unchanged.
 * {@code destinationChangeEnabled} is the vehicle saying it can take a new destination now; null
 * means it did not say, which a site that waits for the signal treats as no.
 *
 * <p>{@code mode} says who drives the vehicle (MANUAL, SEMI_AUTO, REMOTE) and {@code status} what
 * it is doing (IDLE, MOVING, ERROR, PAUSED, CHARGING, LOADING, UNLOADING, RESET), the two axes
 * vehicles report separately. When given they decide the flags the controller acts on: only REMOTE
 * is under the controller's command, and only IDLE is ready for a new movement. When not given they
 * are derived from those flags, so an older report still reads the same.
 */
public record VehicleReport(Long nodeId, boolean online, boolean remote, boolean idle,
                            boolean carrierPresent, String source, Boolean destinationChangeEnabled,
                            String mode, String status) {
    public static final Set<String> MODES=Set.of("MANUAL","SEMI_AUTO","REMOTE");
    public static final Set<String> STATUSES=Set.of("IDLE","MOVING","ERROR","PAUSED","CHARGING","LOADING","UNLOADING","RESET");

    public VehicleReport {
        if(mode!=null){
            mode=mode.trim().toUpperCase();
            if(!MODES.contains(mode)) throw new IllegalArgumentException("INVALID_MODE");
            remote="REMOTE".equals(mode);
        }else mode=remote?"REMOTE":"MANUAL";
        if(status!=null){
            status=status.trim().toUpperCase();
            if(!STATUSES.contains(status)) throw new IllegalArgumentException("INVALID_STATUS");
            idle="IDLE".equals(status);
        }else status=idle?"IDLE":"MOVING";
    }
    public VehicleReport(Long nodeId,boolean online,boolean remote,boolean idle,boolean carrierPresent,String source){
        this(nodeId,online,remote,idle,carrierPresent,source,null,null,null);
    }
    public VehicleReport(Long nodeId,boolean online,boolean remote,boolean idle,boolean carrierPresent,String source,
                         Boolean destinationChangeEnabled){
        this(nodeId,online,remote,idle,carrierPresent,source,destinationChangeEnabled,null,null);
    }
}
