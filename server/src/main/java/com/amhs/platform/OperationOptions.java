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
 int moreMinRemainingNodes,boolean moreVehicleSignal,String mergeControl,int transferMaxAttempts,String runMode,
 Integer yieldAfterSeconds,Integer homeReturnSeconds,Integer chargeRequestPct,Integer chargeCriticalPct,Integer chargeReleasePct){
 /**
  * yieldAfterSeconds: how long a vehicle may wait for one standing idle in its way before that one
  * is moved aside (0 never). homeReturnSeconds: how long a vehicle may stand without work before it
  * is sent to its home node (0 never). The charge values are state of charge in percent: below
  * request an AGV asks to charge and work still wins; below critical, without a carrier, it must
  * charge and is given no work; above release the request ends.
  */
 public OperationOptions{
  if(yieldAfterSeconds==null) yieldAfterSeconds=5;
  if(homeReturnSeconds==null) homeReturnSeconds=0;
  if(chargeRequestPct==null) chargeRequestPct=40;
  if(chargeCriticalPct==null) chargeCriticalPct=20;
  if(chargeReleasePct==null) chargeReleasePct=80;
 }
 public OperationOptions(String commType,int nodeCount,boolean moreEnabled,String moreType,
  int moreRemainingNodes,int moreRemainingPercent,
  boolean autoDispatch,int commandMaxAttempts,int commandRetrySeconds,int positionStaleSeconds,
  int moreMinRemainingNodes,boolean moreVehicleSignal,String mergeControl,int transferMaxAttempts,String runMode){
  this(commType,nodeCount,moreEnabled,moreType,moreRemainingNodes,moreRemainingPercent,autoDispatch,commandMaxAttempts,
   commandRetrySeconds,positionStaleSeconds,moreMinRemainingNodes,moreVehicleSignal,mergeControl,transferMaxAttempts,runMode,
   null,null,null,null,null);
 }
 public static final Set<String> COMM_TYPES=Set.of("PC","PLC","MQTT");
 public static final Set<String> MORE_TYPES=Set.of("NODE","DISTANCE");
 public static final Set<String> MERGE_CONTROLS=Set.of("CONTROLLER","VEHICLE_SIGNAL");
 /** SIMULATION: vehicles simulated inside the server carry out every movement and transfer. OPERATION: real devices. */
 public static final Set<String> RUN_MODES=Set.of("SIMULATION","OPERATION");
 public boolean simulated(){return "SIMULATION".equals(runMode);}
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
  ConfigurationException.require(RUN_MODES.contains(runMode==null?"":runMode),"INVALID_RUN_MODE");
  ConfigurationException.require(yieldAfterSeconds>=0&&yieldAfterSeconds<=600,"INVALID_YIELD_AFTER_SECONDS");
  ConfigurationException.require(homeReturnSeconds>=0&&homeReturnSeconds<=3600,"INVALID_HOME_RETURN_SECONDS");
  ConfigurationException.require(chargeCriticalPct>=1&&chargeCriticalPct<chargeRequestPct
   &&chargeRequestPct<chargeReleasePct&&chargeReleasePct<=100,"INVALID_CHARGE_LEVELS");
 }
}
