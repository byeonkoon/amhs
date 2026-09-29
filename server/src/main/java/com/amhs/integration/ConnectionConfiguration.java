package com.amhs.integration;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ConnectionConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;

 public ConnectionConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}
 private final RowMapper<ConfigurationRecord<ConnectionSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new ConnectionSettings(r.getString("comm_type"),r.getString("role"),r.getString("host"),r.getInt("port"),r.getBoolean("enabled")));
 public List<ConfigurationRecord<ConnectionSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM integration.connection_profile WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<ConnectionSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM integration.connection_profile WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"CONNECTION_NOT_FOUND");return rows.getFirst();
 }
 private void validate(long site,long id,ConnectionSettings s){s.validate();}
 @Transactional
 public ConfigurationRecord<ConnectionSettings> create(long site,ConnectionSettings s){
  guard.acquire(site);validate(site,-1,s);long id=guard.nextId();
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,role,host,port,enabled) VALUES(?,?,?,?,?,?,?)",id,site,s.commType(),s.role(),s.host(),s.port(),s.enabled());
  return get(site,id);
 }
 @Transactional
 public ConfigurationRecord<ConnectionSettings> update(long site,long id,long expectedVersion,ConnectionSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");if(jdbc.queryForObject("SELECT COUNT(*) FROM integration.vehicle_communication WHERE site_id=? AND connection_profile_id=?",Integer.class,site,id)>0)throw new ConfigurationException(409,"PROFILE_IN_USE");
  validate(site,id,s);
  guard.changed(jdbc.update("UPDATE integration.connection_profile SET comm_type=?,role=?,host=?,port=?,enabled=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.commType(),s.role(),s.host(),s.port(),s.enabled(),site,id,expectedVersion));
  return get(site,id);
 }
}
