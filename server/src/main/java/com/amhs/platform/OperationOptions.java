package com.amhs.platform;
import java.util.Set;
/**
 * Site-wide options. These are not one machine's parameters: they apply to every vehicle and
 * every command at the site, which is why they are configured apart from the equipment screens
 * and need the highest authority to change.
 *
 * <p>The communication type applies to every vehicle of the site; nodeCount is the most nodes one
 * movement authority may cover.
 *
 * <p>MORE extends a running movement authority. NODE issues it when at most moreRemainingNodes
 * authorized nodes remain; DISTANCE when the remaining authorized distance is at most
 * moreRemainingPercent of one full authority along the route ahead.
 *
 * <p>moreMinRemainingNodes is the floor under MORE: with fewer authorized nodes than this left, the
 * vehicle is already slowing for its stop and a renewal could not be acted on, so none is sent.
 * moreVehicleSignal makes MORE also wait for the vehicle to report that it can accept a changed
 * destination. mergeControl says who decides entry at a merge point: CONTROLLER grants it in
 * request order; VEHICLE_SIGNAL leaves that order to the vehicles (signal lights read on board)
 * and the controller only keeps one vehicle per node.
 *
 * <p>Every option here changes behaviour that exists. autoDispatch decides whether a site sweep
 * assigns queued work or only extends what is already running; the command values decide how a
 * send that provably failed is retried; positionStaleSeconds decides when a position that stopped
 * arriving is shown as stale rather than current; transferMaxAttempts bounds how often a pickup or
 * drop that provably did not happen is tried again before the order goes to recovery.
 */
public record OperationOptions(String commType,int nodeCount,boolean moreEnabled,String moreType,
 int moreRemainingNodes,int moreRemainingPercent,
 boolean autoDispatch,int commandMaxAttempts,int commandRetrySeconds,int positionStaleSeconds,
 int moreMinRemainingNodes,boolean moreVehicleSignal,String mergeControl,int transferMaxAttempts){
 public static final Set<String> COMM_TYPES=Set.of("PC","PLC","MQTT");
 public static final Set<String> MORE_TYPES=Set.of("NODE","DISTANCE");
 public static final Set<String> MERGE_CONTROLS=Set.of("CONTROLLER","VEHICLE_SIGNAL");
 public boolean usesVda(){return "MQTT".equals(commType);}
 public void validate(){
  ConfigurationException.require(COMM_TYPES.contains(commType==null?"":commType),"INVALID_COMM_TYPE");
  ConfigurationException.require(nodeCount>=1&&nodeCount<=100,"INVALID_NODE_COUNT");
  ConfigurationException.require(MORE_TYPES.contains(moreType==null?"":moreType),"INVALID_MORE_TYPE");
  ConfigurationException.require(moreRemainingNodes>=1&&moreRemainingNodes<=99,"INVALID_MORE_REMAINING_NODES");
  // Otherwise every new authority would already satisfy the threshold and MORE would fire at once.
  ConfigurationException.require(moreRemainingNodes<nodeCount,"MORE_NODES_NOT_BELOW_NODE_COUNT");
  ConfigurationException.require(moreRemainingPercent>=1&&moreRemainingPercent<=99,"INVALID_MORE_REMAINING_PERCENT");
  ConfigurationException.require(commandMaxAttempts>=1&&commandMaxAttempts<=20,"INVALID_COMMAND_MAX_ATTEMPTS");
  ConfigurationException.require(commandRetrySeconds>=1&&commandRetrySeconds<=3600,"INVALID_COMMAND_RETRY_SECONDS");
  ConfigurationException.require(positionStaleSeconds>=1&&positionStaleSeconds<=3600,"INVALID_POSITION_STALE_SECONDS");
  ConfigurationException.require(moreMinRemainingNodes>=0&&moreMinRemainingNodes<=99,"INVALID_MORE_MIN_REMAINING_NODES");
  // A floor above the trigger would leave no node count at which MORE could ever be sent.
  ConfigurationException.require(moreMinRemainingNodes<=moreRemainingNodes,"MORE_MIN_ABOVE_TRIGGER");
  ConfigurationException.require(MERGE_CONTROLS.contains(mergeControl==null?"":mergeControl),"INVALID_MERGE_CONTROL");
  ConfigurationException.require(transferMaxAttempts>=1&&transferMaxAttempts<=10,"INVALID_TRANSFER_MAX_ATTEMPTS");
 }
}
