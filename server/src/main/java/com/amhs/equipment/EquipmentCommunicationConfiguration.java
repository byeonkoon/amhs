package com.amhs.equipment;
import com.amhs.integration.ConnectionConfiguration;
import com.amhs.integration.ProtocolConfiguration;
import com.amhs.integration.VehicleAdapters;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.platform.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * Which equipment is wired to what.
 *
 * <p>The same two profiles a vehicle uses. A stocker behind a PLC is the same arrangement as an AGV
 * behind one -- an address, a dialect, and a name the far side knows it by -- and a second kind of
 * connection profile would be the same thing maintained twice, drifting apart at the first change.
 *
 * <p>What is different is the dialect. A document for equipment is an {@code EQUIPMENT_WORD_V1}
 * document, because what it may report is a different vocabulary: a stocker has no node and no
 * battery, and a document that could give it one is a document somebody will eventually write.
 */
@Service
public class EquipmentCommunicationConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final EquipmentConfiguration units; private final ConnectionConfiguration connections;
 private final ProtocolConfiguration protocols; private final MappingLibrary mappings;

 public EquipmentCommunicationConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,EquipmentConfiguration units,
                                            ConnectionConfiguration connections,ProtocolConfiguration protocols,
                                            MappingLibrary mappings){
  this.jdbc=jdbc; this.guard=guard; this.units=units; this.connections=connections;
  this.protocols=protocols; this.mappings=mappings;
 }

 private final RowMapper<ConfigurationRecord<EquipmentCommunicationSettings>> mapper=(r,n)->
  new ConfigurationRecord<>(r.getLong("id"),r.getLong("version"),
   new EquipmentCommunicationSettings(r.getLong("unit_id"),r.getLong("connection_profile_id"),
    r.getLong("protocol_profile_id"),r.getString("external_unit_id"),r.getBoolean("enabled")));

 public List<ConfigurationRecord<EquipmentCommunicationSettings>> list(long site){
  guard.requireSite(site);
  return jdbc.query("SELECT * FROM equipment.communication WHERE site_id=? ORDER BY id",mapper,site);
 }
 public ConfigurationRecord<EquipmentCommunicationSettings> get(long site,long id){
  var rows=jdbc.query("SELECT * FROM equipment.communication WHERE site_id=? AND id=?",mapper,site,id);
  if(rows.isEmpty()) throw new ConfigurationException(404,"EQUIPMENT_COMMUNICATION_NOT_FOUND");
  return rows.getFirst();
 }

 private void validate(long site,EquipmentCommunicationSettings s){
  s.validate();
  units.unit(site,s.unitId());
  var c=connections.get(site,s.connectionProfileId()).settings();
  var p=protocols.get(site,s.protocolProfileId()).settings();
  if(s.enabled()){
   // In use means there is something to put it in use on. The dialect must be one that describes
   // equipment, and the link must be one this build can open and switched on itself -- otherwise
   // the row says a stocker is connected while nothing ever reads it.
   var document=mappings.byCode(site,p.adapterKey())
    .filter(m->VehicleAdapters.EQUIPMENT_WORD_V1.equals(m.adapter()));
   ConfigurationException.require(document.isPresent(),"EQUIPMENT_ADAPTER_REQUIRED");
   ConfigurationException.require(c.enabled()&&"TCP".equals(c.commType()),"CONNECTION_NOT_IN_USE");
   // A dialled-in gateway has no address to poll, and equipment is read by polling.
   ConfigurationException.require("CLIENT".equals(c.role()),"EQUIPMENT_NEEDS_A_DIALLED_LINK");
  }
 }

 @Transactional
 public ConfigurationRecord<EquipmentCommunicationSettings> create(long site,EquipmentCommunicationSettings s){
  guard.acquire(site); validate(site,s);
  long id=guard.nextId();
  jdbc.update("INSERT INTO equipment.communication(id,site_id,unit_id,connection_profile_id,protocol_profile_id,"
   +"external_unit_id,enabled) VALUES(?,?,?,?,?,?,?)",id,site,s.unitId(),s.connectionProfileId(),
   s.protocolProfileId(),s.externalUnitId(),s.enabled());
  return get(site,id);
 }

 @Transactional
 public ConfigurationRecord<EquipmentCommunicationSettings> update(long site,long id,long expectedVersion,
                                                                   EquipmentCommunicationSettings s){
  guard.acquire(site); get(site,id);
  ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  validate(site,s);
  guard.changed(jdbc.update("UPDATE equipment.communication SET unit_id=?,connection_profile_id=?,"
   +"protocol_profile_id=?,external_unit_id=?,enabled=?,version=version+1 WHERE site_id=? AND id=? AND version=?",
   s.unitId(),s.connectionProfileId(),s.protocolProfileId(),s.externalUnitId(),s.enabled(),site,id,expectedVersion));
  return get(site,id);
 }

 /**
  * Takes the wiring away.
  *
  * <p>What the device last said goes with it. Keeping it would leave a unit reading as READY on a
  * screen with nothing behind that reading any more, which is worse than reading as unknown.
  */
 @Transactional
 public void delete(long site,long id,long expectedVersion){
  guard.acquire(site);
  var row=get(site,id);
  ConfigurationException.require(expectedVersion==row.version(),"VERSION_CONFLICT");
  jdbc.update("DELETE FROM equipment.device_state WHERE unit_id=?",row.settings().unitId());
  guard.changed(jdbc.update("DELETE FROM equipment.communication WHERE site_id=? AND id=? AND version=?",
   site,id,expectedVersion));
 }
}
