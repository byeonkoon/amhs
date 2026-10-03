package com.amhs.equipment;

import com.amhs.platform.ConfigurationException;
import com.amhs.platform.OperationOptionConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Local equipment controls. No device connection or protocol configuration is consulted. */
@Service
public class SimulatedEquipment {
 public static class Prohibited extends RuntimeException {
  public Prohibited(String code){super(code);}
 }
 private final JdbcTemplate jdbc;
 private final OperationOptionConfiguration options;
 public SimulatedEquipment(JdbcTemplate jdbc,OperationOptionConfiguration options){this.jdbc=jdbc;this.options=options;}
 public record State(long unitId,boolean configuredEnabled,boolean enabled,boolean callEnabled,String status){}
 public record Change(Boolean enabled,Boolean callEnabled,String status){}
 private static final Set<String> STATES=Set.of("IDLE","READY","BUSY","ERROR","OFFLINE","LOADING","UNLOADING");

 public void requireSimulation(long site){
  if(!options.get(site).settings().simulated()) throw new ConfigurationException(409,"SITE_NOT_IN_SIMULATION");
 }
 public List<State> list(long site){
  requireSimulation(site);
  return jdbc.query("SELECT u.id,u.enabled,COALESCE(s.enabled,TRUE),COALESCE(s.call_enabled,TRUE),COALESCE(s.status,'IDLE')"
   +" FROM equipment.unit u LEFT JOIN equipment.simulation_state s ON s.unit_id=u.id"
   +" JOIN topology.map_revision m ON m.id=u.revision_id AND m.status='ACTIVE' WHERE u.site_id=? ORDER BY u.id",
   (r,n)->new State(r.getLong(1),r.getBoolean(2),r.getBoolean(3),r.getBoolean(4),r.getString(5)),site);
 }
 @Transactional
 public State change(long site,long unit,Change change){
  requireSimulation(site);
  var found=jdbc.queryForList("SELECT u.id FROM equipment.unit u JOIN topology.map_revision m ON m.id=u.revision_id"
   +" WHERE u.site_id=? AND u.id=? AND m.status='ACTIVE' FOR UPDATE OF u",Long.class,site,unit);
  if(found.isEmpty()) throw new ConfigurationException(404,"EQUIPMENT_NOT_FOUND");
  var old=list(site).stream().filter(s->s.unitId()==unit).findFirst().orElseThrow();
  if(change.status()!=null){
   if(!STATES.contains(change.status())) throw new ConfigurationException(422,"INVALID_EQUIPMENT_STATE");
   if(!old.configuredEnabled()||!old.enabled()||!old.callEnabled())
    throw new ConfigurationException(409,"EQUIPMENT_PROHIBITED");
  }
  jdbc.update("INSERT INTO equipment.simulation_state(unit_id,enabled,call_enabled,status) VALUES(?,?,?,?)"
   +" ON CONFLICT(unit_id) DO UPDATE SET enabled=excluded.enabled,call_enabled=excluded.call_enabled,"
   +"status=excluded.status,updated_at=now()",unit,change.enabled()==null?old.enabled():change.enabled(),
   change.callEnabled()==null?old.callEnabled():change.callEnabled(),change.status()==null?old.status():change.status());
  return list(site).stream().filter(s->s.unitId()==unit).findFirst().orElseThrow();
 }

 /** Called inside intake/assignment transactions; a prohibition edit takes the same unit lock. */
 @Transactional
 public void requireAvailable(long site,long... ports){
  if(!options.get(site).settings().simulated()) return;
  var ids=new java.util.TreeSet<Long>();
  for(long port:ports) ids.addAll(jdbc.queryForList("SELECT equipment_unit_id FROM topology.port"
   +" WHERE site_id=? AND id=? AND equipment_unit_id IS NOT NULL",Long.class,site,port));
  for(long unit:ids){
   var enabled=jdbc.queryForList("SELECT enabled FROM equipment.unit WHERE site_id=? AND id=? FOR UPDATE",Boolean.class,site,unit);
   if(enabled.isEmpty()||!enabled.getFirst()) throw new Prohibited("EQUIPMENT_DISABLED");
   var state=jdbc.query("SELECT enabled,call_enabled FROM equipment.simulation_state WHERE unit_id=?",
    (r,n)->new boolean[]{r.getBoolean(1),r.getBoolean(2)},unit);
   if(!state.isEmpty()){
    if(!state.getFirst()[0]) throw new Prohibited("EQUIPMENT_DISABLED");
    if(!state.getFirst()[1]) throw new Prohibited("EQUIPMENT_CALL_DISABLED");
   }
  }
 }

 /** Hold the prohibition lock through a virtual pickup/drop and record its local status atomically. */
 @Transactional
 public <T> T transfer(long site,long port,String status,Supplier<T> action){
  requireSimulation(site);
  requireAvailable(site,port);
  T result=action.get();
  jdbc.update("INSERT INTO equipment.simulation_state(unit_id,status)"
   +" SELECT equipment_unit_id,? FROM topology.port WHERE site_id=? AND id=? AND equipment_unit_id IS NOT NULL"
   +" ON CONFLICT(unit_id) DO UPDATE SET status=excluded.status,updated_at=now()",status,site,port);
  return result;
 }
}
