package com.amhs.transport.infrastructure;
import com.amhs.platform.VehicleType;
import com.amhs.transport.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.time.OffsetDateTime;
import java.util.*;
@Service
class JdbcTransportLedger implements TransportLedger {
 private final JdbcTemplate jdbc;
 JdbcTransportLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}

 @Transactional(readOnly=true)
 public long activeRevision(long siteId){
  var ids=jdbc.query("SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'",(r,n)->r.getLong(1),siteId);
  if(ids.isEmpty())throw new TransportRejected("NO_ACTIVE_MAP","site has no active map revision",false);
  return ids.get(0);
 }
 @Transactional(readOnly=true)
 public Optional<PortAccess> findPort(long siteId,long revisionId,String portCode){
  var rows=jdbc.query("SELECT id,code,access_vehicle_type FROM topology.port WHERE site_id=? AND revision_id=? AND code=?",
   (r,n)->new PortAccess(r.getLong(1),r.getString(2),VehicleType.valueOf(r.getString(3))),siteId,revisionId,portCode);
  return rows.isEmpty()?Optional.empty():Optional.of(rows.get(0));
 }
 @Transactional(readOnly=true)
 public Optional<TransportOrder> findByIdempotency(long siteId,String requestSource,String idempotencyKey){
  return one("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND request_source=? AND idempotency_key=?",siteId,requestSource,idempotencyKey);
 }
 @Transactional(readOnly=true)
 public String fingerprintOf(long orderId){
  var rows=jdbc.query("SELECT request_fingerprint FROM transport.order WHERE id=?",(r,n)->r.getString(1),orderId);
  return rows.isEmpty()?null:rows.get(0);
 }
 @Transactional(readOnly=true)
 public Optional<TransportOrder> find(long siteId,long orderId){
  return one("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND id=?",siteId,orderId);
 }
 @Transactional(readOnly=true)
 public List<TransportOrder> list(long siteId,OrderStatus status,int limit){
  return status==null
   ?jdbc.query("SELECT "+COLS+" FROM transport.order WHERE site_id=? ORDER BY id DESC LIMIT ?",MAPPER,siteId,limit)
   :jdbc.query("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND status=? ORDER BY id DESC LIMIT ?",MAPPER,siteId,status.name(),limit);
 }
 @Transactional(readOnly=true)
 public List<OrderEventView> events(long siteId,long orderId){
  return jdbc.query("SELECT e.seq,e.old_status,e.new_status,e.reason,e.actor_code,e.occurred_at FROM transport.order_event e"
   +" JOIN transport.order o ON o.id=e.order_id WHERE o.site_id=? AND e.order_id=? ORDER BY e.seq",
   (r,n)->new OrderEventView(r.getInt(1),r.getString(2)==null?null:OrderStatus.valueOf(r.getString(2)),
    OrderStatus.valueOf(r.getString(3)),r.getString(4),r.getString(5),r.getObject(6,OffsetDateTime.class)),siteId,orderId);
 }

 /** Order row and its first event share one transaction; a half-written intake is never visible. */
 @Transactional
 public TransportOrder create(TransportOrder d,String fingerprint,String actorCode,String traceId){
  // RETURNING is used instead of generated-key extraction: PostgreSQL hands back the whole row,
  // which makes a single-key lookup ambiguous.
  Long id=jdbc.queryForObject("INSERT INTO transport.order(site_id,revision_id,request_source,idempotency_key,request_fingerprint,"
   +"source_port_id,destination_port_id,vehicle_type,carrier_code,priority,status,carrier_location)"
   +" VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,
   d.siteId(),d.revisionId(),d.requestSource(),d.idempotencyKey(),fingerprint,
   d.sourcePortId(),d.destinationPortId(),d.vehicleType().name(),d.carrierCode(),d.priority(),
   d.status().name(),d.carrierLocation().name());
  Objects.requireNonNull(id,"insert returned no id");
  jdbc.update("INSERT INTO transport.order_event(order_id,seq,old_status,new_status,reason,actor_code,trace_id)"
   +" VALUES(?,?,?,?,?,?,?)",id,1,null,d.status().name(),"accepted",actorCode,traceId);
  return find(d.siteId(),id).orElseThrow();
 }

 /**
  * Checked transition. The row is locked first so two concurrent callers cannot both read the same
  * source state and both write. A rejected transition leaves no event behind.
  */
 @Transactional
 public TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId){
  var locked=one("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND id=? FOR UPDATE",siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  var current=locked.status();
  if(current==target)return locked;
  if(!current.canTransitionTo(target))
   throw new TransportRejected("TRANSITION_NOT_ALLOWED",current+" cannot move to "+target,true);
  // A cancellation acknowledgement does not establish where the carrier ended up.
  // Decide under the same row lock used for the state update and event append.
  if(current==OrderStatus.CANCEL_REQUESTED&&target==OrderStatus.CANCELLED
    &&(locked.carrierLocation()==CarrierLocation.UNKNOWN||locked.carrierLocation()==CarrierLocation.VEHICLE)){
   target=OrderStatus.RECOVERY_REQUIRED;
   reason="CANCEL_REQUIRES_CARRIER_RECOVERY"+(reason==null||reason.isBlank()?"":": "+reason);
  }
  boolean starting=locked.startedAt()==null&&target!=OrderStatus.QUEUED;
  jdbc.update("UPDATE transport.order SET status=?,version=version+1"
   +",started_at=CASE WHEN ? THEN now() ELSE started_at END"
   +",terminal_at=CASE WHEN ? THEN now() ELSE terminal_at END"
   +",cancel_reason=CASE WHEN ? THEN ? ELSE cancel_reason END"
   +" WHERE site_id=? AND id=?",
   target.name(),starting,target.isTerminal(),
   target==OrderStatus.CANCELLED||target==OrderStatus.CANCEL_REQUESTED,reason,siteId,orderId);
  // A finished order must not keep holding its vehicle, so the release happens with the
  // transition rather than in a later step that could be missed.
  if(target.isTerminal())
   jdbc.update("UPDATE transport.assignment SET released_at=now(),release_reason=?"
    +" WHERE order_id=? AND released_at IS NULL",target.name(),orderId);
  Integer seq=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM transport.order_event WHERE order_id=?",Integer.class,orderId);
  jdbc.update("INSERT INTO transport.order_event(order_id,seq,old_status,new_status,reason,actor_code,trace_id)"
   +" VALUES(?,?,?,?,?,?,?)",orderId,seq,current.name(),target.name(),reason,actorCode,traceId);
  return find(siteId,orderId).orElseThrow();
 }

 @Transactional(propagation=Propagation.MANDATORY)
 public Optional<TransportOrder> lock(long siteId,long orderId){
  return one("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND id=? FOR UPDATE",siteId,orderId);
 }

 /**
  * The filtered unique indexes, not this method, settle the race: a second active assignment for
  * the same vehicle or the same order is refused by the database, which rolls the whole decision
  * back rather than leaving one vehicle holding two jobs.
  */
 @Transactional(propagation=Propagation.MANDATORY)
 public long assign(long orderId,long vehicleId){
  try{
   return jdbc.queryForObject("INSERT INTO transport.assignment(order_id,vehicle_id) VALUES(?,?) RETURNING id",
    Long.class,orderId,vehicleId);
  }catch(DuplicateKeyException e){
   throw new TransportRejected("ASSIGNMENT_CONFLICT","vehicle or order is already assigned",true);
  }
 }

 @Transactional(readOnly=true)
 public Optional<AssignmentView> activeAssignment(long siteId,long orderId){
  var rows=jdbc.query("SELECT a.id,a.order_id,a.vehicle_id,a.assigned_at,a.released_at,a.release_reason"
   +" FROM transport.assignment a JOIN transport.order o ON o.id=a.order_id"
   +" WHERE o.site_id=? AND a.order_id=? AND a.released_at IS NULL",
   (r,n)->new AssignmentView(r.getLong(1),r.getLong(2),r.getLong(3),
    r.getObject(4,OffsetDateTime.class),r.getObject(5,OffsetDateTime.class),r.getString(6)),siteId,orderId);
  return rows.isEmpty()?Optional.empty():Optional.of(rows.getFirst());
 }

 @Transactional(readOnly=true)
 public List<Long> assignedVehicleIds(long siteId){
  return jdbc.query("SELECT a.vehicle_id FROM transport.assignment a JOIN transport.order o ON o.id=a.order_id"
   +" WHERE o.site_id=? AND a.released_at IS NULL",(r,n)->r.getLong(1),siteId);
 }

 private Optional<TransportOrder> one(String sql,Object... args){
  var rows=jdbc.query(sql,MAPPER,args);
  return rows.isEmpty()?Optional.empty():Optional.of(rows.get(0));
 }
 private static final String COLS="id,site_id,revision_id,request_source,idempotency_key,source_port_id,destination_port_id,"
  +"vehicle_type,carrier_code,priority,status,carrier_location,version,created_at,started_at,terminal_at,cancel_reason";
 private static final org.springframework.jdbc.core.RowMapper<TransportOrder> MAPPER=(r,n)->new TransportOrder(
  r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getLong(6),r.getLong(7),
  VehicleType.valueOf(r.getString(8)),r.getString(9),r.getInt(10),OrderStatus.valueOf(r.getString(11)),
  CarrierLocation.valueOf(r.getString(12)),r.getLong(13),
  r.getObject(14,OffsetDateTime.class),r.getObject(15,OffsetDateTime.class),r.getObject(16,OffsetDateTime.class),r.getString(17));
}
