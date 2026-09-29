package com.amhs.integration;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ProtocolConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;

 public ProtocolConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}
 private final RowMapper<ConfigurationRecord<ProtocolSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new ProtocolSettings(r.getString("proto_type"),r.getString("proto_version"),r.getString("adapter_key")));
 public List<ConfigurationRecord<ProtocolSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM integration.protocol_profile WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<ProtocolSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM integration.protocol_profile WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"PROTOCOL_NOT_FOUND");return rows.getFirst();
 }
 private void validate(long site,long id,ProtocolSettings s){s.validate();}
 @Transactional
 public ConfigurationRecord<ProtocolSettings> create(long site,ProtocolSettings s){
  guard.acquire(site);validate(site,-1,s);long id=guard.nextId();
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key) VALUES(?,?,?,?,?)",id,site,s.protoType(),s.protoVersion(),s.adapterKey());
  return get(site,id);
 }
 @Transactional
 public ConfigurationRecord<ProtocolSettings> update(long site,long id,long expectedVersion,ProtocolSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");if(jdbc.queryForObject("SELECT COUNT(*) FROM integration.vehicle_communication WHERE site_id=? AND protocol_profile_id=?",Integer.class,site,id)>0)throw new ConfigurationException(409,"PROFILE_IN_USE");
  validate(site,id,s);
  guard.changed(jdbc.update("UPDATE integration.protocol_profile SET proto_type=?,proto_version=?,adapter_key=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.protoType(),s.protoVersion(),s.adapterKey(),site,id,expectedVersion));
  return get(site,id);
 }
}
