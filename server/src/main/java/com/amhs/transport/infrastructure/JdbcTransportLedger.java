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
 public TransportOrder transition(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId,
                                  TransitionEvidence evidence){
  if(evidence==null) evidence=TransitionEvidence.system();
  var locked=one("SELECT "+COLS+" FROM transport.order WHERE site_id=? AND id=? FOR UPDATE",siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  var current=locked.status();
  if(current==target)return locked;
  if(!current.canTransitionTo(target))
   throw new TransportRejected("TRANSITION_NOT_ALLOWED",current+" cannot move to "+target,true);
  // A claim only a person can make needs a person behind it, not the bare API or nobody.
  if(evidence.kind().byPerson()&&!isPerson(actorCode))
   throw new TransportRejected("EVIDENCE_NEEDS_PRINCIPAL",evidence.kind()+" must come from an identified person",false);
  var established=evidence.kind().establishes()?evidence.carrierLocation():null;
  // Finishing a pickup or a drop moves the carrier, and only evidence of where it went may say so.
  // Going back to try again needs evidence that the carrier did not move.
  var required=requiredLocation(current,target);
  if(required!=null&&established!=required)
   throw new TransportRejected(evidenceCode(current,target),
    current+" to "+target+" needs evidence that the carrier is at "+required,true);
  boolean retry=(current==OrderStatus.LOADING&&target==OrderStatus.TO_SOURCE)
   ||(current==OrderStatus.UNLOADING&&target==OrderStatus.TO_DESTINATION);
  if(retry){
   Integer attempts=jdbc.queryForObject("SELECT transfer_attempt FROM transport.order WHERE id=?",Integer.class,orderId);
   // Trying again forever is not recovery: past the site's limit a person has to look.
   if((attempts==null?0:attempts)+1>=transferLimit(siteId)){
    target=OrderStatus.RECOVERY_REQUIRED; retry=false;
    reason="TRANSFER_ATTEMPTS_EXHAUSTED"+(reason==null||reason.isBlank()?"":": "+reason);
   }
  }
  var effective=established!=null?established:locked.carrierLocation();
  boolean carrierUnsettled=effective==CarrierLocation.UNKNOWN||effective==CarrierLocation.VEHICLE;
  // A cancellation acknowledgement does not establish where the carrier ended up once a pickup
  // has begun. A cancel requested before any pickup (the vehicle was only driving there) leaves
  // the carrier where it always was. Decide under the same row lock used for the state update.
  if(current==OrderStatus.CANCEL_REQUESTED&&target==OrderStatus.CANCELLED&&carrierUnsettled&&pickupBegan(orderId)){
   target=OrderStatus.RECOVERY_REQUIRED;
   reason="CANCEL_REQUIRES_CARRIER_RECOVERY"+(reason==null||reason.isBlank()?"":": "+reason);
  }
  // Leaving recovery is a statement about where the carrier is. Without one the protection would
  // end on the next acknowledgement, which is exactly what it exists to stop. A repeated cancel
  // confirmation says nothing new and changes nothing.
  if(current==OrderStatus.RECOVERY_REQUIRED){
   if(carrierUnsettled){
    if(target==OrderStatus.CANCELLED) return locked;
    throw new TransportRejected("RECOVERY_EVIDENCE_REQUIRED","where the carrier is must be recorded first",true);
   }
   boolean consistent=switch(target){
    case COMPLETED -> effective==CarrierLocation.DESTINATION;
    case CANCELLED -> effective==CarrierLocation.SOURCE;
    default -> true;
   };
   if(!consistent)
    throw new TransportRejected("RECOVERY_LOCATION_MISMATCH",
     "the carrier is recorded at "+effective+", which does not end the order as "+target,true);
  }
  boolean starting=locked.startedAt()==null&&target!=OrderStatus.QUEUED;
  jdbc.update("UPDATE transport.order SET status=?,version=version+1"
   +",carrier_location=COALESCE(CAST(? AS VARCHAR),carrier_location)"
   +",transfer_attempt=transfer_attempt+CASE WHEN ? THEN 1 ELSE 0 END"
   +",started_at=CASE WHEN ? THEN now() ELSE started_at END"
   +",terminal_at=CASE WHEN ? THEN now() ELSE terminal_at END"
   +",cancel_reason=CASE WHEN ? THEN ? ELSE cancel_reason END"
   +" WHERE site_id=? AND id=?",
   target.name(),established==null?null:established.name(),retry,starting,target.isTerminal(),
   target==OrderStatus.CANCELLED||target==OrderStatus.CANCEL_REQUESTED,reason,siteId,orderId);
  // A finished order must not keep holding its vehicle, so the release happens with the
  // transition rather than in a later step that could be missed.
  if(target.isTerminal())
   jdbc.update("UPDATE transport.assignment SET released_at=now(),release_reason=?"
    +" WHERE order_id=? AND released_at IS NULL",target.name(),orderId);
  Integer seq=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM transport.order_event WHERE order_id=?",Integer.class,orderId);
  jdbc.update("INSERT INTO transport.order_event(order_id,seq,old_status,new_status,reason,actor_code,trace_id,"
   +"evidence_kind,command_id,carrier_location) VALUES(?,?,?,?,?,?,?,?,?,?)",
   orderId,seq,current.name(),target.name(),reason,actorCode,traceId,
   evidence.kind().name(),evidence.commandId(),established==null?null:established.name());
  return find(siteId,orderId).orElseThrow();
 }

 /** Where the carrier must be shown to be for this step, or null when the step does not move it. */
 private static CarrierLocation requiredLocation(OrderStatus from,OrderStatus to){
  if(from==OrderStatus.LOADING&&to==OrderStatus.TO_DESTINATION) return CarrierLocation.VEHICLE;
  if(from==OrderStatus.UNLOADING&&to==OrderStatus.COMPLETED) return CarrierLocation.DESTINATION;
  if(from==OrderStatus.LOADING&&to==OrderStatus.TO_SOURCE) return CarrierLocation.SOURCE;
  if(from==OrderStatus.UNLOADING&&to==OrderStatus.TO_DESTINATION) return CarrierLocation.VEHICLE;
  return null;
 }
 private static String evidenceCode(OrderStatus from,OrderStatus to){
  if(to==OrderStatus.TO_DESTINATION&&from==OrderStatus.LOADING) return "PICKUP_EVIDENCE_REQUIRED";
  if(to==OrderStatus.COMPLETED) return "DROP_EVIDENCE_REQUIRED";
  return "RETRY_EVIDENCE_REQUIRED";
 }
 /** The bare API and the anonymous caller are not people; every account and the local admin are. */
 private static boolean isPerson(String actorCode){
  return actorCode!=null&&!actorCode.isBlank()&&!"api".equals(actorCode)&&!"anonymous".equals(actorCode);
 }
 private int transferLimit(long siteId){
  var rows=jdbc.query("SELECT transfer_max_attempts FROM platform.site_operation WHERE site_id=?",(r,n)->r.getInt(1),siteId);
  return rows.isEmpty()?3:rows.getFirst();
 }

 /** Whether this order ever reached the pickup, which is when the carrier may have changed hands. */
 private boolean pickupBegan(long orderId){
  Boolean began=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transport.order_event WHERE order_id=? AND new_status='LOADING')",
   Boolean.class,orderId);
  return Boolean.TRUE.equals(began);
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
 public long assign(long orderId,long vehicleId,List<Long> routeNodeIds,int pickupIndex){
  try{
   return jdbc.queryForObject("INSERT INTO transport.assignment(order_id,vehicle_id,route_node_ids,pickup_index)"
    +" VALUES(?,?,?,?) RETURNING id",Long.class,orderId,vehicleId,routeNodeIds.toArray(Long[]::new),pickupIndex);
  }catch(DuplicateKeyException e){
   throw new TransportRejected("ASSIGNMENT_CONFLICT","vehicle or order is already assigned",true);
  }
 }

 @Transactional(readOnly=true)
 public List<Long> plannedRoute(long orderId){
  var rows=jdbc.query("SELECT route_node_ids FROM transport.assignment"
   +" WHERE order_id=? AND released_at IS NULL ORDER BY id DESC LIMIT 1",
   (r,n)->{
    var array=r.getArray(1);
    if(array==null) return List.<Long>of();
    return List.of((Long[])array.getArray());
   },orderId);
  return rows.isEmpty()?List.of():rows.getFirst();
 }

 @Transactional(readOnly=true)
 public int routeCursor(long orderId){
  var rows=jdbc.query("SELECT route_cursor FROM transport.assignment"
   +" WHERE order_id=? AND released_at IS NULL ORDER BY id DESC LIMIT 1",(r,n)->r.getInt(1),orderId);
  return rows.isEmpty()?0:rows.getFirst();
 }

 /** The cursor only moves forward: a report from behind the vehicle's known progress changes nothing. */
 @Transactional(propagation=Propagation.MANDATORY)
 public void moveRouteCursor(long orderId,int cursor){
  jdbc.update("UPDATE transport.assignment SET route_cursor=? WHERE order_id=? AND released_at IS NULL"
   +" AND route_cursor<?",cursor,orderId,cursor);
 }

 @Transactional(readOnly=true)
 public int pickupIndex(long orderId){
  var rows=jdbc.query("SELECT pickup_index FROM transport.assignment"
   +" WHERE order_id=? AND released_at IS NULL ORDER BY id DESC LIMIT 1",
   (r,n)->r.getObject(1)==null?-1:r.getInt(1),orderId);
  return rows.isEmpty()?-1:rows.getFirst();
 }

 /** The cursor is kept: a replacement only ever changes what lies ahead of the vehicle. */
 @Transactional(propagation=Propagation.MANDATORY)
 public void replaceRoute(long orderId,List<Long> routeNodeIds,int pickupIndex){
  jdbc.update("UPDATE transport.assignment SET route_node_ids=?,pickup_index=? WHERE order_id=? AND released_at IS NULL",
   routeNodeIds.toArray(Long[]::new),pickupIndex<0?null:pickupIndex,orderId);
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
 public List<AssignmentView> activeAssignments(long siteId){
  return jdbc.query("SELECT a.id,a.order_id,a.vehicle_id,a.assigned_at,a.released_at,a.release_reason"
   +" FROM transport.assignment a JOIN transport.order o ON o.id=a.order_id"
   +" WHERE o.site_id=? AND a.released_at IS NULL ORDER BY a.order_id",
   (r,n)->new AssignmentView(r.getLong(1),r.getLong(2),r.getLong(3),
    r.getObject(4,OffsetDateTime.class),r.getObject(5,OffsetDateTime.class),r.getString(6)),siteId);
 }

 @Transactional(readOnly=true)
 public List<Long> assignedOrderIds(long siteId){
  return jdbc.query("SELECT a.order_id FROM transport.assignment a JOIN transport.order o ON o.id=a.order_id"
   +" WHERE o.site_id=? AND a.released_at IS NULL ORDER BY a.order_id",(r,n)->r.getLong(1),siteId);
 }

 @Transactional(readOnly=true)
 public List<Long> assignedVehicleIds(long siteId){
  return jdbc.query("SELECT a.vehicle_id FROM transport.assignment a JOIN transport.order o ON o.id=a.order_id"
   +" WHERE o.site_id=? AND a.released_at IS NULL",(r,n)->r.getLong(1),siteId);
 }

 @Transactional(readOnly=true)
 public Long assignedVehicleOf(long orderId){
  var rows=jdbc.query("SELECT vehicle_id FROM transport.assignment WHERE order_id=? ORDER BY assigned_at DESC,id DESC LIMIT 1",
   (r,n)->r.getLong(1),orderId);
  return rows.isEmpty()?null:rows.getFirst();
 }

 private static final String RELOCATION_COLS=
  "id,site_id,revision_id,vehicle_id,zone_id,route_node_ids,target_node_id,started_at";
 private static final org.springframework.jdbc.core.RowMapper<Relocation> RELOCATION=(r,n)->new Relocation(
  r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),
  List.of((Long[])r.getArray(6).getArray()),r.getLong(7),r.getObject(8,OffsetDateTime.class));

 @Transactional(readOnly=true)
 public List<Relocation> openRelocations(long siteId){
  return jdbc.query("SELECT "+RELOCATION_COLS+" FROM transport.relocation WHERE site_id=? AND finished_at IS NULL"
   +" ORDER BY id",RELOCATION,siteId);
 }
 @Transactional(readOnly=true)
 public List<Long> relocatingVehicleIds(long siteId){
  return jdbc.query("SELECT vehicle_id FROM transport.relocation WHERE site_id=? AND finished_at IS NULL",
   (r,n)->r.getLong(1),siteId);
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public Relocation startRelocation(long siteId,long revisionId,long vehicleId,long zoneId,List<Long> routeNodeIds,long targetNodeId){
  try{
   return jdbc.queryForObject("INSERT INTO transport.relocation(site_id,revision_id,vehicle_id,zone_id,route_node_ids,target_node_id)"
    +" VALUES(?,?,?,?,?,?) RETURNING "+RELOCATION_COLS,RELOCATION,
    siteId,revisionId,vehicleId,zoneId,routeNodeIds.toArray(Long[]::new),targetNodeId);
  }catch(DuplicateKeyException e){
   throw new TransportRejected("RELOCATION_CONFLICT","vehicle is already being moved",true);
  }
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void replaceRelocationRoute(long relocationId,List<Long> routeNodeIds){
  jdbc.update("UPDATE transport.relocation SET route_node_ids=? WHERE id=? AND finished_at IS NULL",
   routeNodeIds.toArray(Long[]::new),relocationId);
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void finishRelocation(long relocationId,String reason){
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason=? WHERE id=? AND finished_at IS NULL",
   reason==null?null:reason.length()<=32?reason:reason.substring(0,32),relocationId);
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
