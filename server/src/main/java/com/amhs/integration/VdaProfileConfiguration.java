package com.amhs.integration;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class VdaProfileConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public VdaProfileConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}
 private final RowMapper<ConfigurationRecord<VdaProfileSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new VdaProfileSettings(r.getString("code"),r.getString("interface_name"),r.getString("topic_version"),r.getString("manufacturer"),r.getString("json_version"),r.getString("source_action"),r.getString("dest_action")));
 public List<ConfigurationRecord<VdaProfileSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM integration.vda_profile WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<VdaProfileSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM integration.vda_profile WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"VDA_PROFILE_NOT_FOUND");return rows.getFirst();
 }
 @Transactional
 public ConfigurationRecord<VdaProfileSettings> create(long site,VdaProfileSettings s){
  guard.acquire(site);s.validate();long id=guard.nextId();
  jdbc.update("INSERT INTO integration.vda_profile(id,site_id,code,interface_name,topic_version,manufacturer,json_version,source_action,dest_action) VALUES(?,?,?,?,?,?,?,?,?)",id,site,s.code(),s.interfaceName(),s.topicVersion(),s.manufacturer(),s.jsonVersion(),s.sourceAction(),s.destAction());
  return get(site,id);
 }
 /** Editing a profile in use is allowed: the site is in maintenance and every vehicle naming it follows the change. */
 @Transactional
 public ConfigurationRecord<VdaProfileSettings> update(long site,long id,long expectedVersion,VdaProfileSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  guard.changed(jdbc.update("UPDATE integration.vda_profile SET code=?,interface_name=?,topic_version=?,manufacturer=?,json_version=?,source_action=?,dest_action=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.code(),s.interfaceName(),s.topicVersion(),s.manufacturer(),s.jsonVersion(),s.sourceAction(),s.destAction(),site,id,expectedVersion));
  return get(site,id);
 }
}
