package com.amhs.fleet.infrastructure;
import com.amhs.fleet.*;
import com.amhs.platform.VehicleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
@Service
class JdbcFleetState implements FleetState {
 private final JdbcTemplate jdbc;
 JdbcFleetState(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /**
  * How far below the reading before it a reading must be to count as having fallen rather than
  * wobbled. It is a noise floor, so it is kept well under the smallest fall worth noticing: set too
  * high, a slow genuine drain reads as flat and nothing is ever noticed.
  */
 private static final double FALL_EPSILON=0.1;

 /**
  * A configured vehicle with no report yet is listed with everything false and no position, so
  * it is visible as "not reporting" instead of disappearing from the fleet.
  */
 @Transactional(readOnly=true)
 public List<VehicleSnapshot> snapshot(long siteId,long revisionId){
  return jdbc.query(SELECT+" ORDER BY p.id",MAPPER,siteId,revisionId);
 }
 @Transactional(readOnly=true)
 public Optional<VehicleSnapshot> find(long siteId,long revisionId,long vehicleId){
  var rows=jdbc.query(SELECT+" AND p.id=?",MAPPER,siteId,revisionId,vehicleId);
  return rows.isEmpty()?Optional.empty():Optional.of(rows.getFirst());
 }

 @Transactional
 public VehicleSnapshot report(long siteId,long revisionId,long vehicleId,VehicleReport report){
  int known=jdbc.queryForObject("SELECT count(*) FROM fleet.vehicle_profile WHERE site_id=? AND revision_id=? AND id=?",
   Integer.class,siteId,revisionId,vehicleId);
  if(known==0) throw new IllegalArgumentException("VEHICLE_NOT_FOUND");
  if(report.nodeId()!=null){
   int onMap=jdbc.queryForObject("SELECT count(*) FROM topology.node WHERE site_id=? AND revision_id=? AND id=?",
    Integer.class,siteId,revisionId,report.nodeId());
   // A position that is not on this revision's map is not a position; it is a bad report.
   if(onMap==0) throw new IllegalArgumentException("NODE_NOT_IN_REVISION");
  }
  // The wall clock, not the transaction's start: a report must be orderable against a decision
  // taken earlier in the same transaction (a stop asked of the vehicle, for one).
  jdbc.update("INSERT INTO fleet.vehicle_state(site_id,vehicle_id,revision_id,node_id,online,remote,idle,carrier_present,"
   +"source,destination_change_enabled,mode,status,reported_at,idle_since) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),"
   +"CASE WHEN ? THEN clock_timestamp() END)"
   +" ON CONFLICT(site_id,vehicle_id) DO UPDATE SET"
   +" revision_id=EXCLUDED.revision_id,node_id=EXCLUDED.node_id,online=EXCLUDED.online,remote=EXCLUDED.remote,"
   +" idle=EXCLUDED.idle,carrier_present=EXCLUDED.carrier_present,source=EXCLUDED.source,"
   +" destination_change_enabled=EXCLUDED.destination_change_enabled,mode=EXCLUDED.mode,status=EXCLUDED.status,"
   +" x_mm=NULL,y_mm=NULL,reported_at=clock_timestamp(),version=fleet.vehicle_state.version+1,"
   // Idle since it first said so standing on a node; a later idle report keeps that time.
   +" idle_since=CASE WHEN EXCLUDED.idle THEN COALESCE(CASE WHEN fleet.vehicle_state.idle AND fleet.vehicle_state.x_mm IS NULL"
   +"  THEN fleet.vehicle_state.idle_since END,clock_timestamp()) END",
   siteId,vehicleId,revisionId,report.nodeId(),report.online(),report.remote(),report.idle(),
   report.carrierPresent(),report.source(),report.destinationChangeEnabled(),report.mode(),report.status(),report.idle());
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 @Transactional
 public VehicleSnapshot reportModeAndStatus(long siteId,long revisionId,long vehicleId,String mode,String status){
  // The same names and checks as any report.
  var checked=new VehicleReport(null,true,false,false,false,"SIMULATED",null,mode,status);
  int changed=jdbc.update("UPDATE fleet.vehicle_state SET mode=?,remote=?,"
   +"status=CASE WHEN x_mm IS NOT NULL AND ?='REMOTE' AND ?='IDLE' THEN 'MOVING' ELSE ? END,"
   +"idle_since=CASE WHEN x_mm IS NULL AND ?='IDLE' THEN COALESCE(CASE WHEN idle THEN idle_since END,clock_timestamp()) END,"
   +"idle=(x_mm IS NULL AND ?='IDLE'),reported_at=clock_timestamp(),version=version+1"
   +" WHERE site_id=? AND vehicle_id=? AND revision_id=? AND node_id IS NOT NULL",
   checked.mode(),checked.remote(),checked.mode(),checked.status(),checked.status(),checked.status(),checked.status(),
   siteId,vehicleId,revisionId);
  if(changed==0) throw new IllegalArgumentException("POSITION_UNKNOWN");
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 @Transactional
 public void reportBattery(long siteId,long revisionId,long vehicleId,double batteryPct){
  if(!Double.isFinite(batteryPct)||batteryPct<0||batteryPct>100) throw new IllegalArgumentException("INVALID_BATTERY");
  // Where the current fall began is kept with the reading, so whether a charge has been going down
  // can be answered without a history. The anchor holds only while readings keep coming in lower
  // than the one before; any reading that is not meaningfully lower ends that fall and moves the
  // anchor to here and now.
  //
  // "Not lower" has to include flat, not just rising. A buffer the rail holds full reports the same
  // value all day, and if only a rise moved the anchor it would sit frozen at whenever the vehicle
  // first reached full -- so a single dip hours later would look like hours of falling, and the
  // window that exists to ride out transients would never be applied at all.
  //
  // The epsilon is the noise floor of a reading: a difference that cannot be told from jitter is
  // not a fall. Every expression on the right sees the row as it was, so the new reading is
  // compared with the previous one even though both are set here.
  jdbc.update("UPDATE fleet.vehicle_state SET "
   +"soc_peak_pct=CASE WHEN battery_pct IS NULL OR soc_peak_pct IS NULL OR ?>=battery_pct-? THEN ? ELSE soc_peak_pct END,"
   +"soc_peak_at=CASE WHEN battery_pct IS NULL OR soc_peak_pct IS NULL OR ?>=battery_pct-? THEN now() ELSE soc_peak_at END,"
   +"battery_pct=?"
   +" WHERE site_id=? AND vehicle_id=? AND revision_id=?",
   batteryPct,FALL_EPSILON,batteryPct,batteryPct,FALL_EPSILON,batteryPct,siteId,vehicleId,revisionId);
 }

 @Transactional
 public VehicleSnapshot takeOutOfService(long siteId,long revisionId,long vehicleId,String reason,String by){
  // Why has to be recorded. A vehicle found standing out of service with nothing saying why is
  // either put back by somebody guessing, or left there by everybody assuming somebody knew.
  if(reason==null||reason.isBlank()) throw new IllegalArgumentException("OUT_OF_SERVICE_REASON_REQUIRED");
  int changed=jdbc.update("UPDATE fleet.vehicle_state SET out_of_service_since=COALESCE(out_of_service_since,now()),"
   +"out_of_service_reason=?,out_of_service_by=? WHERE site_id=? AND vehicle_id=? AND revision_id=?",
   reason.substring(0,Math.min(reason.length(),256)),by,siteId,vehicleId,revisionId);
  if(changed==0) throw new IllegalArgumentException("VEHICLE_NOT_REPORTING");
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 @Transactional
 public VehicleSnapshot returnToService(long siteId,long revisionId,long vehicleId){
  int changed=jdbc.update("UPDATE fleet.vehicle_state SET out_of_service_since=NULL,out_of_service_reason=NULL,"
   +"out_of_service_by=NULL WHERE site_id=? AND vehicle_id=? AND revision_id=?",siteId,vehicleId,revisionId);
  if(changed==0) throw new IllegalArgumentException("VEHICLE_NOT_REPORTING");
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 @Transactional
 public VehicleSnapshot reportDriving(long siteId,long revisionId,long vehicleId,double xMm,double yMm){
  int changed=jdbc.update("UPDATE fleet.vehicle_state SET x_mm=?,y_mm=?,status='MOVING',idle=FALSE,idle_since=NULL,"
   +"reported_at=clock_timestamp(),version=version+1 WHERE site_id=? AND vehicle_id=? AND revision_id=? AND node_id IS NOT NULL",
   xMm,yMm,siteId,vehicleId,revisionId);
  if(changed==0) throw new IllegalArgumentException("POSITION_UNKNOWN");
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 @Transactional
 public VehicleSnapshot reportBetweenNodes(long siteId,long revisionId,long vehicleId,double xMm,double yMm){
  int changed=jdbc.update("UPDATE fleet.vehicle_state SET x_mm=?,y_mm=?,reported_at=clock_timestamp(),"
   +"version=version+1 WHERE site_id=? AND vehicle_id=? AND revision_id=? AND node_id IS NOT NULL",
   xMm,yMm,siteId,vehicleId,revisionId);
  // Between nodes means having left a known node; without one there is nothing to be between.
  if(changed==0) throw new IllegalArgumentException("POSITION_UNKNOWN");
  return find(siteId,revisionId,vehicleId).orElseThrow();
 }

 /**
  * Ascending id order is the whole point: two assignments that want overlapping vehicles take
  * the same locks in the same sequence, so one waits instead of both holding half of what the
  * other needs. Carrier and slot claims join this ordering when they arrive.
  */
 @Transactional(propagation=Propagation.MANDATORY)
 public void lockForAssignment(long siteId,List<Long> vehicleIds){
  var ordered=vehicleIds.stream().distinct().sorted().toList();
  for(Long id:ordered)
   jdbc.query("SELECT vehicle_id FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=? FOR UPDATE",
    (r,n)->r.getLong(1),siteId,id);
 }

 private static final String SELECT="SELECT p.id,p.code,p.v_type,s.node_id,p.enabled,p.assignment_enabled,"
  // Between two nodes a vehicle is not idle, whatever it says: nothing may treat it as standing.
  +"COALESCE(s.online,FALSE),COALESCE(s.remote,FALSE),COALESCE(s.idle,FALSE) AND s.x_mm IS NULL,COALESCE(s.carrier_present,FALSE),"
  +"COALESCE(s.source,'INITIAL_CONFIGURATION'),s.reported_at,s.destination_change_enabled,p.home_node_id,s.mode,s.status,"
  +"s.x_mm,s.y_mm,p.charge_node_id,p.speed_mm_s,s.battery_pct,p.charging_policy,s.idle_since,"
  +"s.soc_peak_pct,s.soc_peak_at,s.out_of_service_since,s.out_of_service_reason,s.out_of_service_by"
  +" FROM fleet.vehicle_profile p LEFT JOIN fleet.vehicle_state s ON s.site_id=p.site_id AND s.vehicle_id=p.id"
  +" WHERE p.site_id=? AND p.revision_id=?";
 // reported_at stays null until something reports, so a screen can tell "never" from "a while ago".
 private static final RowMapper<VehicleSnapshot> MAPPER=(r,n)->new VehicleSnapshot(
  r.getLong(1),r.getString(2),VehicleType.valueOf(r.getString(3)),
  r.getObject(4)==null?null:r.getLong(4),
  r.getBoolean(5),r.getBoolean(6),r.getBoolean(7),r.getBoolean(8),r.getBoolean(9),r.getBoolean(10),r.getString(11),
  r.getObject(12,java.time.OffsetDateTime.class),r.getObject(13,Boolean.class),r.getObject(14,Long.class),
  r.getString(15),r.getString(16),r.getObject(17,Double.class),r.getObject(18,Double.class),
  r.getObject(19,Long.class),r.getInt(20),r.getObject(21,Double.class),r.getString(22),
  r.getObject(23,java.time.OffsetDateTime.class),
  r.getObject(24,Double.class),r.getObject(25,java.time.OffsetDateTime.class),
  r.getObject(26,java.time.OffsetDateTime.class),r.getString(27),r.getString(28));
}
