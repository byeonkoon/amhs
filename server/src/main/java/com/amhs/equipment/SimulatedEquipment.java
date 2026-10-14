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
   +" WHERE u.site_id=? ORDER BY u.id",
   (r,n)->new State(r.getLong(1),r.getBoolean(2),r.getBoolean(3),r.getBoolean(4),r.getString(5)),site);
 }
 @Transactional
 public State change(long site,long unit,Change change){
  requireSimulation(site);
  var found=jdbc.queryForList("SELECT u.id FROM equipment.unit u"
   +" WHERE u.site_id=? AND u.id=? FOR UPDATE OF u",Long.class,site,unit);
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
 /**
  * Whether the equipment behind these ports will take what is about to be sent to it.
  *
  * <p>Which answer counts depends on where the state honestly comes from. In SIMULATION it is what
  * somebody set, to try something out. In OPERATION it is what the machine said down its link --
  * and a machine that is wired but has not been heard from is refused, because a stocker nobody
  * has heard from is exactly the one not to send a carrier to.
  *
  * <p>A unit nobody has wired at all is not refused. It is a site that has not described its
  * equipment to this controller yet, which is how every site starts, and refusing on that would
  * stop work over a fact nobody has stated either way.
  */
 @Transactional
 public void requireAvailable(long site,long... ports){
  boolean simulated=options.get(site).settings().simulated();
  var ids=new java.util.TreeSet<Long>();
  for(long port:ports) ids.addAll(jdbc.queryForList("SELECT equipment_unit_id FROM topology.port"
   +" WHERE site_id=? AND id=? AND equipment_unit_id IS NOT NULL",Long.class,site,port));
  for(long unit:ids){
   // Taken before the answer is worked out, so a prohibition edit cannot land between the two.
   jdbc.queryForList("SELECT enabled FROM equipment.unit WHERE site_id=? AND id=? FOR UPDATE",Boolean.class,site,unit);
   var refusal=refusalFor(site,unit,simulated);
   if(refusal!=null) throw new Prohibited(refusal);
  }
 }

 /**
  * Why this unit would refuse work right now, or null when it would not.
  *
  * <p>The same answer the screens show. Written once because two copies of this rule would be two
  * rules: an operator reading "ready" off a screen while transport refuses the port has been given
  * a reason to distrust the screen, and there is no way back from that.
  */
 public String refusalFor(long site,long unit,boolean simulated){
  var enabled=jdbc.queryForList("SELECT enabled FROM equipment.unit WHERE site_id=? AND id=?",Boolean.class,site,unit);
  if(enabled.isEmpty()||!enabled.getFirst()) return "EQUIPMENT_DISABLED";
  if(simulated){
   var state=jdbc.query("SELECT enabled,call_enabled FROM equipment.simulation_state WHERE unit_id=?",
    (r,n)->new boolean[]{r.getBoolean(1),r.getBoolean(2)},unit);
   if(state.isEmpty()) return null;
   if(!state.getFirst()[0]) return "EQUIPMENT_DISABLED";
   if(!state.getFirst()[1]) return "EQUIPMENT_CALL_DISABLED";
   return null;
  }
  return whatTheMachineSaid(site,unit);
 }

 /** What the machine itself last said, for a unit somebody has wired to one. */
 private String whatTheMachineSaid(long site,long unit){
  var wired=jdbc.queryForObject("SELECT count(*) FROM equipment.communication"
   +" WHERE site_id=? AND unit_id=? AND enabled",Integer.class,site,unit);
  if(wired==null||wired==0) return null;        // not described to this controller; not a refusal
  var state=jdbc.query("SELECT online,available,call_enabled,status FROM equipment.device_state WHERE unit_id=?",
   (r,n)->new Object[]{r.getBoolean(1),r.getObject(2),r.getObject(3),r.getString(4)},unit);
  // Wired and never heard from is not "fine so far". Nothing is known, and acting on nothing is
  // how a carrier ends up in front of a machine that has been in fault since before the shift.
  if(state.isEmpty()||!(Boolean)state.getFirst()[0]) return "EQUIPMENT_OFFLINE";
  var row=state.getFirst();
  if(Boolean.FALSE.equals(row[1])) return "EQUIPMENT_DISABLED";
  if(Boolean.FALSE.equals(row[2])) return "EQUIPMENT_CALL_DISABLED";
  if("ERROR".equals(row[3])||"OFFLINE".equals(row[3])) return "EQUIPMENT_IN_FAULT";
  return null;
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
