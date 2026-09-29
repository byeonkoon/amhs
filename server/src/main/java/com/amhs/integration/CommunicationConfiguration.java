package com.amhs.integration;
import com.amhs.platform.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
@Service
public class CommunicationConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final com.amhs.fleet.VehicleConfiguration vehicles; private final ConnectionConfiguration connections; private final ProtocolConfiguration protocols;
 public CommunicationConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,com.amhs.fleet.VehicleConfiguration vehicles,ConnectionConfiguration connections,ProtocolConfiguration protocols){this.jdbc=jdbc;this.guard=guard;this.vehicles=vehicles;this.connections=connections;this.protocols=protocols;}
 private final RowMapper<ConfigurationRecord<CommunicationSettings>> mapper=(r,n)->new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),new CommunicationSettings(r.getLong("vehicle_id"),r.getLong("connection_profile_id"),r.getLong("protocol_profile_id"),r.getString("manufacturer"),r.getString("external_vehicle_id"),r.getString("serial_number"),r.getString("interface_name"),r.getBoolean("enabled")));
 public List<ConfigurationRecord<CommunicationSettings>> list(long site){guard.requireSite(site);return jdbc.query("SELECT * FROM integration.vehicle_communication WHERE site_id=? ORDER BY id",mapper,site);}
 public ConfigurationRecord<CommunicationSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM integration.vehicle_communication WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"COMMUNICATION_NOT_FOUND");return rows.getFirst();
 }
 private void validate(long site,long id,CommunicationSettings s){s.validate();vehicles.get(site,s.vehicleId());
 var c=connections.get(site,s.connectionProfileId()).settings();var p=protocols.get(site,s.protocolProfileId()).settings();
 ConfigurationException.require(("MQTT".equals(c.commType()))==("VDA5050".equals(p.protoType())),"UNSUPPORTED_PROTOCOL_COMBINATION");
 if("VDA5050".equals(p.protoType()))ConfigurationException.require(s.serialNumber()!=null&&s.interfaceName()!=null,"VDA_IDENTITY_REQUIRED");
 else ConfigurationException.require(s.serialNumber()==null&&s.interfaceName()==null,"VDA_IDENTITY_NOT_APPLICABLE");}
 @Transactional
 public ConfigurationRecord<CommunicationSettings> create(long site,CommunicationSettings s){
  guard.acquire(site);validate(site,-1,s);long id=guard.nextId();
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,protocol_profile_id,manufacturer,external_vehicle_id,serial_number,interface_name,enabled) VALUES(?,?,?,?,?,?,?,?,?,?)",id,site,s.vehicleId(),s.connectionProfileId(),s.protocolProfileId(),s.manufacturer(),s.externalVehicleId(),s.serialNumber(),s.interfaceName(),s.enabled());
  return get(site,id);
 }
 @Transactional
 public ConfigurationRecord<CommunicationSettings> update(long site,long id,long expectedVersion,CommunicationSettings s){
  guard.acquire(site);get(site,id);ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  validate(site,id,s);
  guard.changed(jdbc.update("UPDATE integration.vehicle_communication SET vehicle_id=?,connection_profile_id=?,protocol_profile_id=?,manufacturer=?,external_vehicle_id=?,serial_number=?,interface_name=?,enabled=?,version=version+1 WHERE site_id=? AND id=? AND version=?",s.vehicleId(),s.connectionProfileId(),s.protocolProfileId(),s.manufacturer(),s.externalVehicleId(),s.serialNumber(),s.interfaceName(),s.enabled(),site,id,expectedVersion));
  return get(site,id);
 }
}
