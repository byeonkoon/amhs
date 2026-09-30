package com.amhs.platform;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OperationOptionConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public OperationOptionConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}
 /** The record id is the site id: there is exactly one option row per site. */
 public ConfigurationRecord<OperationOptions> get(long site){
  var rows=jdbc.query("SELECT comm_type,node_count,version FROM platform.site_operation WHERE site_id=?",
   (r,n)->new ConfigurationRecord<>(site,r.getLong(3),new OperationOptions(r.getString(1),r.getInt(2))),site);
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
  guard.changed(jdbc.update("UPDATE platform.site_operation SET comm_type=?,node_count=?,version=version+1 WHERE site_id=? AND version=?",s.commType(),s.nodeCount(),site,expectedVersion));
  return get(site);
 }
}
