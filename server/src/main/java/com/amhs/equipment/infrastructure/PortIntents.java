package com.amhs.equipment.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this controller has to say to a handover port, and what it has already said.
 *
 * <p>The thing it knows that the machine does not: a vehicle has been sent to that port. The
 * machine finds out when the vehicle turns up, and a stocker that knew a minute earlier could hold
 * the port, finish what it was doing with it and have the carrier ready. That is the whole of the
 * decision, and it is deliberately the whole of it -- this controller does not run the handover
 * handshake, which is the vehicle's and the port's own business over their own wires.
 *
 * <p>It is a <b>standing statement</b>, not a message: what is true at this port now. So there is
 * no outbox and no retry schedule. Saying it again says the same thing, where sending a movement
 * command again is a second movement -- and that difference is the only reason writing to equipment
 * can be this simple. What is kept here is the last thing said, so the next pass can tell "nothing
 * has changed" from "it has, and nobody has told the machine".
 */
@Component
public class PortIntents {
 /** The statement a port's equipment is owed. */
 public record Intent(long portId,long unitId,String portCode,boolean reserved,String direction,
                      boolean transferring,boolean attention,String carrier){
  /** Nothing is coming to this port, which is what a port says most of the time. */
  static Intent quiet(long portId,long unitId,String portCode){
   return new Intent(portId,unitId,portCode,false,null,false,false,null);
  }
 }
 /** One statement to write: what it says, as text, and which statement it is. */
 public record Due(Intent intent,String statement,int sequence){}

 private final JdbcTemplate jdbc; private final ObjectMapper json;
 PortIntents(JdbcTemplate jdbc,ObjectMapper json){ this.jdbc=jdbc; this.json=json; }

 /**
  * The statement as the mapping document will read it, and as it is compared with the last one.
  *
  * <p>Built in a fixed order so that two statements saying the same thing are the same text. The
  * comparison is the whole of the "has anything changed" question, and a field order that wandered
  * would have the machine told the same thing over and over.
  */
 public ObjectNode payload(Intent intent){
  var node=json.createObjectNode();
  node.put("port",intent.portCode());
  node.put("reserved",intent.reserved());
  node.put("direction",intent.direction());
  node.put("transferring",intent.transferring());
  node.put("attention",intent.attention());
  node.put("carrier",intent.carrier());
  return node;
 }

 /**
  * What this unit's ports are owed, or nothing when they are all up to date.
  *
  * @param refreshMs how long a standing statement may go unrepeated. A controller that restarted
  *        has a block of zeros, which reads as "nothing reserved" -- the safe answer -- so only a
  *        statement that reserves a port is worth repeating, and only that one is.
  */
 public List<Due> dueFor(long siteId,long unitId,long refreshMs){
  var now=currentFor(siteId,unitId);
  var told=toldFor(unitId);
  var out=new ArrayList<Due>();
  now.forEach((portId,intent)->{
   var last=told.get(portId);
   String statement=payload(intent).toString();
   if(last!=null&&"TOLD".equals(last.state())&&statement.equals(last.statement())
    &&(!intent.reserved()||last.ageMs()<refreshMs)) return;
   out.add(new Due(intent,statement,next(last)));
  });
  // A port that had something and now has nothing is owed the saying so. Without this the machine
  // would hold a port for a carrier that was cancelled an hour ago, and nothing would ever
  // contradict it.
  told.forEach((portId,last)->{
   if(now.containsKey(portId)) return;
   var quiet=Intent.quiet(portId,unitId,last.portCode());
   String statement=payload(quiet).toString();
   if("TOLD".equals(last.state())&&statement.equals(last.statement())) return;
   out.add(new Due(quiet,statement,next(last)));
  });
  return out;
 }

 /**
  * How the writing went.
  *
  * <p>IN_DOUBT is kept as the state rather than being retried on a schedule, and the reason is the
  * same one that makes the statement safe: the next pass finds the statement unacknowledged and
  * says it again. There is nothing to be careful about, because the statement has not changed.
  */
 public void told(Intent intent,String statement,int sequence,String state,String error){
  jdbc.update("INSERT INTO equipment.port_intent(port_id,unit_id,statement,sequence,state,last_error,"
   +"told_at,updated_at) VALUES(?,?,?,?,?,?,?,clock_timestamp())"
   +" ON CONFLICT(port_id) DO UPDATE SET unit_id=excluded.unit_id,statement=excluded.statement,"
   +"sequence=excluded.sequence,state=excluded.state,last_error=excluded.last_error,"
   +"told_at=excluded.told_at,updated_at=clock_timestamp()",
   intent.portId(),intent.unitId(),statement,sequence,state,cut(error),
   "NOT_TOLD".equals(state)?null:new java.sql.Timestamp(System.currentTimeMillis()));
 }

 /** The last statement a port was given, as it stands. */
 private record Told(String portCode,String statement,int sequence,String state,long ageMs){}

 /**
  * What each of this unit's ports is owed right now.
  *
  * <p>The stage an order is in says which of its two ports is concerned and which way the carrier
  * is going. An order waiting for a person -- a cancel it has to confirm, a recovery -- is read by
  * the stage it was in when it stopped, because the carrier is still wherever that left it: a port
  * quietly released under a half-finished handover is how a carrier ends up somewhere nobody looks.
  */
 private Map<Long,Intent> currentFor(long siteId,long unitId){
  var out=new LinkedHashMap<Long,Intent>();
  jdbc.query("SELECT p.id,p.code,d.direction,d.transferring,d.attention,d.carrier"
   +" FROM topology.port p"
   +" JOIN topology.map_revision m ON m.id=p.revision_id AND m.status='ACTIVE'"
   +" JOIN LATERAL ("
   +"  SELECT CASE WHEN s.stage IN ('TO_SOURCE','LOADING') THEN 'PICKUP' ELSE 'DROP' END AS direction,"
   +"   (s.stage IN ('LOADING','UNLOADING') AND s.stage=o.status) AS transferring,"
   +"   (o.status IN ('CANCEL_REQUESTED','RECOVERY_REQUIRED')) AS attention,o.carrier_code AS carrier"
   +"  FROM transport.order o CROSS JOIN LATERAL ("
   +"   SELECT CASE WHEN o.status IN ('CANCEL_REQUESTED','RECOVERY_REQUIRED')"
   +"     THEN (SELECT e.old_status FROM transport.order_event e"
   +"           WHERE e.order_id=o.id AND e.new_status=o.status ORDER BY e.seq DESC LIMIT 1)"
   +"     ELSE o.status END AS stage) s"
   +"  WHERE o.site_id=p.site_id"
   +"   AND ((o.source_port_id=p.id AND s.stage IN ('TO_SOURCE','LOADING'))"
   +"     OR (o.destination_port_id=p.id AND s.stage IN ('TO_DESTINATION','UNLOADING')))"
   // One port takes one transfer at a time, so one statement is all a port can be in: the oldest
   // order waiting on it is the one it is about.
   +"  ORDER BY o.id LIMIT 1) d ON TRUE"
   +" WHERE p.site_id=? AND p.equipment_unit_id=?"
   +" ORDER BY p.id",(r,n)->{
    out.put(r.getLong(1),new Intent(r.getLong(1),unitId,r.getString(2),true,r.getString(3),
     r.getBoolean(4),r.getBoolean(5),r.getString(6)));
    return null;
   },siteId,unitId);
  return out;
 }

 private Map<Long,Told> toldFor(long unitId){
  var out=new LinkedHashMap<Long,Told>();
  jdbc.query("SELECT i.port_id,p.code,i.statement,i.sequence,i.state,"
   +"COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-i.told_at)*1000,1e12) AS age_ms"
   +" FROM equipment.port_intent i JOIN topology.port p ON p.id=i.port_id"
   +" WHERE i.unit_id=? ORDER BY i.port_id",(r,n)->{
    out.put(r.getLong(1),new Told(r.getString(2),r.getString(3),r.getInt(4),r.getString(5),
     (long)r.getDouble(6)));
    return null;
   },unitId);
  return out;
 }

 /**
  * Which statement this is.
  *
  * <p>Small and wrapping, because it has to fit whatever slot the document gave it -- a byte, in
  * some documents. 0 is never used: a block of zeroed registers is a controller that has just
  * started, and it must not read as a statement anybody made.
  */
 private static int next(Told last){ return last==null?1:last.sequence()%250+1; }
 private static String cut(String s){ return s==null?null:s.length()<=256?s:s.substring(0,256); }
}
