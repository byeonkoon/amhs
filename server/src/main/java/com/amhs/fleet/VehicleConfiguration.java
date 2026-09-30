package com.amhs.fleet;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class VehicleConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final com.amhs.topology.TopologyValidation topology; private final OperationOptionConfiguration options;
 public VehicleConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,com.amhs.topology.TopologyValidation topology,OperationOptionConfiguration options){this.jdbc=jdbc;this.guard=guard;this.topology=topology;this.options=options;}
 private final RowMapper<ConfigurationRecord<VehicleSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new VehicleSettings(r.getLong("revision_id"),r.getString("code"),r.getString("name"),VehicleType.valueOf(r.getString("v_type")),r.getInt("width_mm"),r.getInt("length_mm"),r.getInt("height_mm"),r.getLong("initial_node_id"),r.getObject("home_node_id",Long.class),r.getObject("charge_node_id",Long.class),r.getBoolean("enabled"),r.getBoolean("assignment_enabled"),r.getString("control_authority"),r.getString("bcr_policy"),r.getString("charging_policy"),r.getObject("vda_profile_id",Long.class)));
 public List<ConfigurationRecord<VehicleSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM fleet.vehicle_profile WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<VehicleSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM fleet.vehicle_profile WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"VEHICLE_NOT_FOUND");return rows.getFirst();
 }
 private void validate(long site,long id,VehicleSettings s){s.validate();topology.vehicleNodes(site,s.revisionId(),s.vType(),s.initialNodeId(),s.homeNodeId(),s.chargeNodeId());vdaProfile(site,s);}
 /**
  * The site communication type decides whether a VDA profile applies. PC/PLC sites leave it empty;
  * on an MQTT site every vehicle in use needs one, because packets cannot be built without it.
  */
 private void vdaProfile(long site,VehicleSettings s){
  if(!options.get(site).settings().usesVda()){ConfigurationException.require(s.vdaProfileId()==null,"VDA_PROFILE_NOT_APPLICABLE");return;}
  if(s.vdaProfileId()==null){ConfigurationException.require(!s.enabled(),"VDA_PROFILE_REQUIRED");return;}
  if(jdbc.queryForObject("SELECT COUNT(*) FROM integration.vda_profile WHERE site_id=? AND id=?",Integer.class,site,s.vdaProfileId())==0)
   throw new ConfigurationException(422,"VDA_PROFILE_NOT_FOUND");
 }
 @Transactional
 public ConfigurationRecord<VehicleSettings> create(long site,VehicleSettings s){
  guard.acquire(site);validate(site,-1,s);long id=guard.nextId();
  jdbc.update("INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,name,v_type,width_mm,length_mm,height_mm,initial_node_id,home_node_id,charge_node_id,enabled,assignment_enabled,control_authority,bcr_policy,charging_policy,vda_profile_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,site,s.revisionId(),s.code(),s.name(),s.vType().name(),s.widthMm(),s.lengthMm(),s.heightMm(),s.initialNodeId(),s.homeNodeId(),s.chargeNodeId(),s.enabled(),s.assignmentEnabled(),s.controlAuthority(),s.bcrPolicy(),s.chargingPolicy(),s.vdaProfileId());
  return get(site,id);
 }
 @Transactional
 public ConfigurationRecord<VehicleSettings> update(long site,long id,long expectedVersion,VehicleSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  validate(site,id,s);
  guard.changed(jdbc.update("UPDATE fleet.vehicle_profile SET revision_id=?,code=?,name=?,v_type=?,width_mm=?,length_mm=?,height_mm=?,initial_node_id=?,home_node_id=?,charge_node_id=?,enabled=?,assignment_enabled=?,control_authority=?,bcr_policy=?,charging_policy=?,vda_profile_id=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.revisionId(),s.code(),s.name(),s.vType().name(),s.widthMm(),s.lengthMm(),s.heightMm(),s.initialNodeId(),s.homeNodeId(),s.chargeNodeId(),s.enabled(),s.assignmentEnabled(),s.controlAuthority(),s.bcrPolicy(),s.chargingPolicy(),s.vdaProfileId(),site,id,expectedVersion));
  return get(site,id);
 }
}
