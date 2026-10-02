package com.amhs.platform;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
/** Reads and writes the site-wide option row that every screen and every command loop consults. */
@Service
public class OperationOptionConfiguration {
 private static final String COLS="comm_type,node_count,more_enabled,more_type,more_remaining_nodes,"
  +"more_remaining_percent,auto_dispatch,command_max_attempts,command_retry_seconds,position_stale_seconds,"
  +"more_min_remaining_nodes,more_vehicle_signal,merge_control,version,transfer_max_attempts,run_mode,"
  +"yield_after_seconds,home_return_seconds,charge_request_pct,charge_critical_pct,charge_release_pct";
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public OperationOptionConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}

 /** Sites currently set to run on simulated vehicles. */
 public java.util.List<Long> simulatedSites(){
  return jdbc.query("SELECT site_id FROM platform.site_operation WHERE run_mode='SIMULATION' ORDER BY site_id",(r,n)->r.getLong(1));
 }

 /** The record id is the site id: there is exactly one option row per site. */
 public ConfigurationRecord<OperationOptions> get(long site){
  var rows=jdbc.query("SELECT "+COLS+" FROM platform.site_operation WHERE site_id=?",
   (r,n)->new ConfigurationRecord<>(site,r.getLong(14),new OperationOptions(
    r.getString(1),r.getInt(2),r.getBoolean(3),r.getString(4),r.getInt(5),r.getInt(6),
    r.getBoolean(7),r.getInt(8),r.getInt(9),r.getInt(10),r.getInt(11),r.getBoolean(12),r.getString(13),r.getInt(15),r.getString(16),
    r.getInt(17),r.getInt(18),r.getInt(19),r.getInt(20),r.getInt(21))),site);
  if(rows.isEmpty())throw new ConfigurationException(404,"SITE_NOT_CONFIGURED");return rows.getFirst();
 }

 /**
  * Leaving MQTT is refused while any vehicle still names a VDA profile, so a vehicle never keeps
  * packet settings the site no longer uses. The profiles are cleared on the vehicles first.
  */
 @Transactional
 public ConfigurationRecord<OperationOptions> update(long site,long expectedVersion,OperationOptions s){
  guard.acquire(site);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  if(!s.usesVda()&&jdbc.queryForObject("SELECT COUNT(*) FROM fleet.vehicle_profile WHERE site_id=? AND vda_profile_id IS NOT NULL",Integer.class,site)>0)
   throw new ConfigurationException(409,"VDA_PROFILE_ASSIGNED");
  guard.changed(jdbc.update("UPDATE platform.site_operation SET comm_type=?,node_count=?,more_enabled=?,"
   +"more_type=?,more_remaining_nodes=?,more_remaining_percent=?,auto_dispatch=?,command_max_attempts=?,"
   +"command_retry_seconds=?,position_stale_seconds=?,more_min_remaining_nodes=?,more_vehicle_signal=?,"
   +"merge_control=?,transfer_max_attempts=?,run_mode=?,yield_after_seconds=?,home_return_seconds=?,"
   +"charge_request_pct=?,charge_critical_pct=?,charge_release_pct=?,version=version+1 WHERE site_id=? AND version=?",
   s.commType(),s.nodeCount(),s.moreEnabled(),s.moreType(),s.moreRemainingNodes(),s.moreRemainingPercent(),
   s.autoDispatch(),s.commandMaxAttempts(),s.commandRetrySeconds(),s.positionStaleSeconds(),
   s.moreMinRemainingNodes(),s.moreVehicleSignal(),s.mergeControl(),s.transferMaxAttempts(),s.runMode(),
   s.yieldAfterSeconds(),s.homeReturnSeconds(),s.chargeRequestPct(),s.chargeCriticalPct(),s.chargeReleasePct(),site,expectedVersion));
  return get(site);
 }
}
