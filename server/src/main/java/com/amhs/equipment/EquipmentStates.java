package com.amhs.equipment;
import com.amhs.platform.OperationOptionConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Each unit as it stands: what it is wired to, what it last said, and whether work would be refused.
 *
 * <p>The link writes what the machine says into a table and transport reads it to decide. Between
 * those two there was nowhere for an operator to look -- so a carrier could be refused for a reason
 * that existed only inside a decision. This is that place.
 *
 * <p>{@code refusal} is not worked out again here. It is the same call transport makes, so a screen
 * saying "ready" while transport refuses the port is not a state this can be in. Two copies of that
 * rule would be two rules, and the one on the screen would be the one somebody trusted.
 */
@Service
public class EquipmentStates {
 private final JdbcTemplate jdbc; private final OperationOptionConfiguration options;
 private final SimulatedEquipment equipment;

 public EquipmentStates(JdbcTemplate jdbc,OperationOptionConfiguration options,SimulatedEquipment equipment){
  this.jdbc=jdbc; this.options=options; this.equipment=equipment;
 }

 /**
  * One unit, as a screen needs it.
  *
  * @param wired whether somebody has told this controller how to reach it
  * @param online whether it has been heard from lately; null when nothing is wired
  * @param status what it last said it was doing, which outlives going quiet
  * @param refusal why work would be refused right now, or null
  * @param fromDevice whether the state above is the machine's own or the simulated one
  */
 public record State(long unitId,String code,String name,String kind,boolean configuredEnabled,
                     boolean wired,Boolean online,Boolean available,Boolean callEnabled,String status,
                     OffsetDateTime lastSeenAt,String refusal,boolean fromDevice){}

 @Transactional(readOnly=true)
 public List<State> list(long site){
  boolean simulated=options.get(site).settings().simulated();
  return jdbc.query("SELECT u.id,u.code,u.name,u.kind,u.enabled,"
   +" (SELECT count(*) FROM equipment.communication c WHERE c.site_id=u.site_id AND c.unit_id=u.id AND c.enabled) AS wired,"
   +" d.online,d.available,d.call_enabled,d.status,d.last_seen_at,"
   +" s.enabled AS sim_enabled,s.call_enabled AS sim_call,s.status AS sim_status"
   +" FROM equipment.unit u"
   // Every unit of the site, which is the set the equipment screen lists beside this. Filtering by
   // the active revision here would show nine machines in one panel and none in the next, with
   // nothing on the screen to explain the difference. A unit no port points at simply never comes
   // up in a decision, so leaving it in the list costs nothing and matching the list costs nothing.
   +" LEFT JOIN equipment.device_state d ON d.unit_id=u.id"
   +" LEFT JOIN equipment.simulation_state s ON s.unit_id=u.id"
   +" WHERE u.site_id=? ORDER BY u.id",(r,n)->{
    long unitId=r.getLong("id");
    boolean wired=r.getInt("wired")>0;
    // Which half of the row is the truth depends on where the site's state honestly comes from.
    // Showing both at once would only ask the operator to decide, which is this system's job.
    Boolean online=simulated?null:(r.getObject("online")==null?null:r.getBoolean("online"));
    Boolean available=simulated?(Boolean)r.getObject("sim_enabled"):(Boolean)r.getObject("available");
    Boolean call=simulated?(Boolean)r.getObject("sim_call"):(Boolean)r.getObject("call_enabled");
    String status=simulated?r.getString("sim_status"):r.getString("status");
    return new State(unitId,r.getString("code"),r.getString("name"),r.getString("kind"),
     r.getBoolean("enabled"),wired,online,available,call,status,
     simulated?null:r.getObject("last_seen_at",OffsetDateTime.class),
     equipment.refusalFor(site,unitId,simulated),!simulated);
   },site);
 }
}
