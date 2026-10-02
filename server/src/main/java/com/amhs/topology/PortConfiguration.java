package com.amhs.topology;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PortConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final TopologyValidation topology;
 public PortConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,TopologyValidation topology){this.jdbc=jdbc;this.guard=guard;this.topology=topology;}
 private final RowMapper<ConfigurationRecord<PortSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new PortSettings(r.getLong("revision_id"),r.getString("code"),r.getString("port_type"),VehicleType.valueOf(r.getString("access_vehicle_type")),r.getLong("linked_node_id"),r.getObject("equipment_unit_id",Long.class)));
 public List<ConfigurationRecord<PortSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM topology.port WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<PortSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM topology.port WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"PORT_NOT_FOUND");return rows.getFirst();
 }
 private void validate(long site,long id,PortSettings s){
  s.validate();topology.portNode(site,s.revisionId(),s.linkedNodeId(),s.accessVehicleType(),id);
  // A port can only hand over to equipment of the same site and map revision.
  if(s.equipmentUnitId()!=null&&jdbc.queryForObject("SELECT count(*) FROM equipment.unit WHERE site_id=? AND revision_id=? AND id=?",
   Integer.class,site,s.revisionId(),s.equipmentUnitId())==0) throw new ConfigurationException(422,"EQUIPMENT_NOT_FOUND");
 }
 @Transactional
 public ConfigurationRecord<PortSettings> create(long site,PortSettings s){
  guard.acquire(site);validate(site,-1,s);long id=guard.nextId();
  jdbc.update("INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,linked_node_id,equipment_unit_id) VALUES(?,?,?,?,?,?,?,?)",id,site,s.revisionId(),s.code(),s.portType(),s.accessVehicleType().name(),s.linkedNodeId(),s.equipmentUnitId());
  return get(site,id);
 }
 @Transactional
 public ConfigurationRecord<PortSettings> update(long site,long id,long expectedVersion,PortSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  validate(site,id,s);
  guard.changed(jdbc.update("UPDATE topology.port SET revision_id=?,code=?,port_type=?,access_vehicle_type=?,linked_node_id=?,equipment_unit_id=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.revisionId(),s.code(),s.portType(),s.accessVehicleType().name(),s.linkedNodeId(),s.equipmentUnitId(),site,id,expectedVersion));
  return get(site,id);
 }
}
