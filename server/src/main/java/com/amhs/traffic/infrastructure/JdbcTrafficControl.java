package com.amhs.traffic.infrastructure;
import com.amhs.traffic.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
@Service
class JdbcTrafficControl implements TrafficControl {
 private final JdbcTemplate jdbc;
 JdbcTrafficControl(JdbcTemplate jdbc){this.jdbc=jdbc;}

 @Transactional
 public ReservationGrant request(long siteId,long revisionId,long vehicleId,Long orderId,List<Long> routeNodeIds){
  var wanted=untilFirstRepeat(routeNodeIds,nodeCount(siteId));
  if(wanted.isEmpty()) return new ReservationGrant(vehicleId,List.of(),true,null,null,"EMPTY_ROUTE");
  if(givingWay(siteId,vehicleId)) return keptOnly(siteId,vehicleId,wanted);

  lockInAscendingIdOrder(siteId,wanted);
  var holders=holdersOf(wanted);
  var standing=standingOn(siteId,wanted,vehicleId);
  var nodes=attributesOf(siteId,revisionId,wanted);
  boolean controllerDecidesMerges="CONTROLLER".equals(mergeControl(siteId));

  var granted=new ArrayList<Long>();
  Long blockedNode=null,blockedBy=null; String reason=null; boolean stopped=false;
  int sequence=nextSequence(siteId,vehicleId);
  for(int i=0;i<wanted.size();i++){
   Long node=wanted.get(i);
   // Where another vehicle says it is standing outranks any permission, this vehicle's included:
   // the ledger records what was allowed, the report what is there. A vehicle that reported a node
   // it was never given still occupies it.
   Long occupant=standing.get(node);
   if(occupant!=null){
    blockedNode=node; blockedBy=occupant;
    reason=granted.isEmpty()?"POSITION_HELD_BY_OTHER_VEHICLE":"NODE_OCCUPIED_BY_OTHER_VEHICLE";
    break;
   }
   Long holder=holders.get(node);
   boolean mine=holder!=null&&holder==vehicleId;
   if(holder!=null&&!mine){
    blockedNode=node; blockedBy=holder;
    // The very first node is where the vehicle already is. Someone else holding it means the
    // permission map and the floor disagree, which is worse than congestion and is named so.
    reason=granted.isEmpty()?"POSITION_HELD_BY_OTHER_VEHICLE":"NODE_HELD_BY_OTHER_VEHICLE";
    break;
   }
   var attributes=nodes.get(node);
   if(attributes==null||!attributes.enabled()){ blockedNode=node; reason="NODE_NOT_AVAILABLE"; break; }
   // The node the vehicle stands on, and anything it already holds, is never refused for being a
   // merge point or inside a zone: refusing what it already has would only strand it.
   if(i>0&&!mine){
    var previous=nodes.get(wanted.get(i-1));
    Long zone=attributes.zoneId();
    if(zone!=null&&!zone.equals(previous==null?null:previous.zoneId())&&!holdsInZone(vehicleId,zone)){
     var refusal=zoneRefusal(siteId,zone,vehicleId);
     if(refusal!=null){ blockedNode=node; reason=refusal; break; }
    }
    if(controllerDecidesMerges&&attributes.mergePoint()){
     Long earlier=earlierWaiter(siteId,node,vehicleId);
     if(earlier!=null){ blockedNode=node; blockedBy=earlier; reason="MERGE_EARLIER_REQUEST"; break; }
    }
   }
   if(!mine){
    jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,order_id,sequence)"
     +" VALUES(?,?,?,?,?,?)",siteId,revisionId,node,vehicleId,orderId,sequence++);
   }
   granted.add(node);
   // A stop node ends the run: the vehicle halts there and is given the rest once it has. The
   // node it already stands on is the exception, or it could never leave a stop node again.
   if(i>0&&attributes.stopNode()){ stopped=i<wanted.size()-1; break; }
  }
  if(blockedNode==null){
   jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=? AND vehicle_id=?",siteId,vehicleId);
   return new ReservationGrant(vehicleId,granted,!stopped,null,null,stopped?"STOP_NODE":null);
  }
  // The first refusal for a node is what "asked first" means. Asking again for the same node
  // keeps that time; asking for a different node starts a new wait.
  jdbc.update("INSERT INTO traffic.reservation_wait(site_id,vehicle_id,wanted_node_id,blocked_by_vehicle_id)"
   +" VALUES(?,?,?,?) ON CONFLICT(site_id,vehicle_id) DO UPDATE SET"
   +" since=CASE WHEN traffic.reservation_wait.wanted_node_id=EXCLUDED.wanted_node_id"
   +"  THEN traffic.reservation_wait.since ELSE now() END,"
   +" wanted_node_id=EXCLUDED.wanted_node_id,blocked_by_vehicle_id=EXCLUDED.blocked_by_vehicle_id,seen_at=now()",
   siteId,vehicleId,blockedNode,blockedBy);
  return new ReservationGrant(vehicleId,granted,false,blockedNode,blockedBy,reason);
 }

 @Transactional
 public Progress advanced(long siteId,long vehicleId,long nodeId){
  var holds=heldBy(siteId,vehicleId);
  var at=holds.stream().filter(h->h.nodeId()==nodeId).findFirst();
  // A vehicle reporting a node it was never given is not evidence we can act on. Releasing what
  // is behind it would hand those nodes to someone else on the strength of a report that already
  // contradicts the permissions we issued.
  if(at.isEmpty()) return new Progress(vehicleId,nodeId,false,0,holds.size());
  int released=jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason='PASSED'"
   +" WHERE site_id=? AND vehicle_id=? AND released_at IS NULL AND sequence<?",siteId,vehicleId,at.get().sequence());
  return new Progress(vehicleId,nodeId,true,released,holds.size()-released);
 }

 @Transactional
 public int releaseAhead(long siteId,long vehicleId,Long positionNodeId,String reason){
  // Whatever the vehicle was waiting for, it no longer wants it. A stale wait would keep its place
  // in the queue at a merge point long after it stopped asking.
  jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=? AND vehicle_id=?",siteId,vehicleId);
  // Nobody knows where it is, so every node in its run is somewhere it might be standing.
  if(positionNodeId==null) return 0;
  var holds=heldBy(siteId,vehicleId);
  var at=holds.stream().filter(h->h.nodeId()==positionNodeId.longValue()).findFirst();
  if(at.isEmpty()) return 0;
  return jdbc.update("UPDATE traffic.node_reservation SET released_at=now(),release_reason=?"
   +" WHERE site_id=? AND vehicle_id=? AND released_at IS NULL AND sequence>?",
   truncate(reason),siteId,vehicleId,at.get().sequence());
 }

 @Transactional(readOnly=true)
 public List<NodeHold> held(long siteId){
  return jdbc.query(SELECT+" WHERE site_id=? AND released_at IS NULL ORDER BY vehicle_id,sequence",MAPPER,siteId);
 }
 @Transactional(readOnly=true)
 public List<NodeHold> heldBy(long siteId,long vehicleId){
  return jdbc.query(SELECT+" WHERE site_id=? AND vehicle_id=? AND released_at IS NULL ORDER BY sequence",
   MAPPER,siteId,vehicleId);
 }

 @Transactional(readOnly=true)
 public List<ZoneOccupancy> zones(long siteId){
  record Row(long id,long revisionId,String code,int capacity,boolean enabled){}
  var rows=jdbc.query("SELECT id,revision_id,code,capacity,enabled FROM topology.zone WHERE site_id=? ORDER BY id",
   (r,n)->new Row(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4),r.getBoolean(5)),siteId);
  var out=new ArrayList<ZoneOccupancy>();
  for(var z:rows){
   var nodeIds=jdbc.query("SELECT node_id FROM topology.zone_node WHERE zone_id=? ORDER BY node_id",
    (r,n)->r.getLong(1),z.id());
   out.add(new ZoneOccupancy(z.id(),z.revisionId(),z.code(),z.capacity(),z.enabled(),nodeIds,vehiclesIn(siteId,z.id(),null)));
  }
  return out;
 }

 @Transactional(readOnly=true)
 public Optional<Deadlock> deadlock(long siteId,long vehicleId){ return cycleFrom(liveWaits(siteId),vehicleId); }

 @Transactional(readOnly=true)
 public List<Deadlock> deadlocks(long siteId){
  var waits=liveWaits(siteId); var found=new LinkedHashMap<List<Long>,Deadlock>();
  for(Long vehicle:waits.keySet())
   cycleFrom(waits,vehicle).ifPresent(d->found.putIfAbsent(d.vehicleIds().stream().sorted().toList(),d));
  return List.copyOf(found.values());
 }

 /**
  * Follows the chain of waits. Each vehicle waits for at most one node, so every step has one
  * successor and the walk either runs out or comes back to somewhere it has been, which is the
  * deadlock itself.
  */
 private Optional<Deadlock> cycleFrom(Map<Long,Wait> waits,long start){
  var order=new ArrayList<Long>(); var seen=new HashMap<Long,Integer>();
  Long current=start;
  while(current!=null){
   Integer prior=seen.get(current);
   if(prior!=null){
    var cycle=List.copyOf(order.subList(prior,order.size()));
    return Optional.of(new Deadlock(cycle,cycle.stream().map(v->waits.get(v).wantedNodeId()).toList()));
   }
   var wait=waits.get(current);
   if(wait==null) return Optional.empty();
   seen.put(current,order.size()); order.add(current);
   current=wait.blockerId();
  }
  return Optional.empty();
 }

 @Transactional(readOnly=true)
 public List<WaitingFor> waitingFor(long siteId){
  return jdbc.query("SELECT DISTINCT ON (w.vehicle_id) w.vehicle_id,w.wanted_node_id,h.vehicle_id,w.since FROM traffic.reservation_wait w"
   +" JOIN (SELECT node_id,vehicle_id FROM traffic.node_reservation WHERE released_at IS NULL"
   +"  UNION SELECT node_id,vehicle_id FROM fleet.vehicle_state WHERE node_id IS NOT NULL) h"
   +"  ON h.node_id=w.wanted_node_id"
   +" WHERE w.site_id=? AND h.vehicle_id<>w.vehicle_id ORDER BY w.vehicle_id,h.vehicle_id",
   (r,n)->new WaitingFor(r.getLong(1),r.getLong(2),r.getLong(3),r.getObject(4,java.time.OffsetDateTime.class)),siteId);
 }

 private record Wait(long vehicleId,long wantedNodeId,Long blockerId){}

 private boolean givingWay(long siteId,long vehicleId){
  return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM traffic.deadlock_recovery"
   +" WHERE site_id=? AND vehicle_id=? AND finished_at IS NULL)",Boolean.class,siteId,vehicleId));
 }
 /** What the vehicle already holds along the route from where it stands; nothing new, no wait. */
 private ReservationGrant keptOnly(long siteId,long vehicleId,List<Long> wanted){
  var mine=new HashSet<Long>();
  for(var h:heldBy(siteId,vehicleId)) mine.add(h.nodeId());
  var kept=new ArrayList<Long>();
  for(Long node:wanted){ if(!mine.contains(node)) break; kept.add(node); }
  Long next=kept.size()<wanted.size()?wanted.get(kept.size()):null;
  return new ReservationGrant(vehicleId,kept,next==null,next,null,next==null?null:"DEADLOCK_RECOVERY");
 }

 @Transactional(readOnly=true)
 public List<DeadlockPlan> deadlockPlans(long siteId){
  var standing=new HashMap<Long,Long>();
  jdbc.query("SELECT node_id,vehicle_id FROM fleet.vehicle_state WHERE site_id=? AND node_id IS NOT NULL",
   (org.springframework.jdbc.core.RowCallbackHandler)r->standing.put(r.getLong(1),r.getLong(2)),siteId);
  var where=new HashMap<Long,Long>();
  standing.forEach((node,vehicle)->where.put(vehicle,node));
  var open=new HashMap<Long,Long>();
  jdbc.query("SELECT vehicle_id,id FROM traffic.deadlock_recovery WHERE site_id=? AND finished_at IS NULL",
   (org.springframework.jdbc.core.RowCallbackHandler)r->open.put(r.getLong(1),r.getLong(2)),siteId);
  var out=new ArrayList<DeadlockPlan>();
  for(var d:deadlocks(siteId)){
   var links=new ArrayList<DeadlockPlan.Link>();
   Long recoveryId=null;
   for(int i=0;i<d.vehicleIds().size();i++){
    long waiter=d.vehicleIds().get(i), node=d.wantedNodeIds().get(i);
    long blocker=d.vehicleIds().get((i+1)%d.vehicleIds().size());
    Long stander=standing.get(node);
    // Giving back the road ahead frees the node only if the blocker holds it after where it stands.
    Long at=where.get(blocker);
    int posSeq=-1, nodeSeq=-1;
    for(var h:heldBy(siteId,blocker)){
     if(at!=null&&h.nodeId()==at) posSeq=h.sequence();
     if(h.nodeId()==node) nodeSeq=h.sequence();
    }
    boolean releasable=stander==null&&posSeq>=0&&nodeSeq>posSeq;
    links.add(new DeadlockPlan.Link(waiter,node,blocker,stander!=null&&stander==blocker,releasable));
    if(recoveryId==null&&open.containsKey(blocker)) recoveryId=open.get(blocker);
    if(recoveryId==null&&open.containsKey(waiter)) recoveryId=open.get(waiter);
   }
   out.add(new DeadlockPlan(d.vehicleIds(),d.wantedNodeIds(),links,recoveryId));
  }
  return out;
 }

 private static final String RECOVERY="SELECT id,site_id,vehicle_id,beneficiary_vehicle_id,wanted_node_id,chain_vehicle_ids,"
  +"status,reason,requested_by,requested_at,stop_command_id,stop_settled_at,released_node_ids,released_at,finished_at,finish_reason"
  +" FROM traffic.deadlock_recovery";
 private static List<Long> longs(java.sql.Array array) throws java.sql.SQLException {
  if(array==null) return List.of();
  var out=new ArrayList<Long>();
  for(Object o:(Object[])array.getArray()) out.add(((Number)o).longValue());
  return out;
 }
 private static final RowMapper<DeadlockRecovery> RECOVERY_MAPPER=(r,n)->new DeadlockRecovery(r.getLong(1),r.getLong(2),
  r.getLong(3),r.getLong(4),r.getLong(5),longs(r.getArray(6)),r.getString(7),r.getString(8),r.getString(9),
  r.getObject(10,OffsetDateTime.class),r.getString(11),r.getObject(12,OffsetDateTime.class),longs(r.getArray(13)),
  r.getObject(14,OffsetDateTime.class),r.getObject(15,OffsetDateTime.class),r.getString(16));
 private static String arrayText(List<Long> ids){
  var b=new StringBuilder("{");
  for(int i=0;i<ids.size();i++){ if(i>0) b.append(','); b.append(ids.get(i)); }
  return b.append('}').toString();
 }

 @Transactional(readOnly=true)
 public List<DeadlockRecovery> recoveries(long siteId,int recentFinished){
  var out=new ArrayList<>(jdbc.query(RECOVERY+" WHERE site_id=? AND finished_at IS NULL ORDER BY requested_at DESC,id DESC",
   RECOVERY_MAPPER,siteId));
  out.addAll(jdbc.query(RECOVERY+" WHERE site_id=? AND finished_at IS NOT NULL ORDER BY finished_at DESC,id DESC LIMIT ?",
   RECOVERY_MAPPER,siteId,Math.max(0,Math.min(recentFinished,100))));
  return out;
 }
 @Transactional(readOnly=true)
 public Optional<DeadlockRecovery> recovery(long siteId,long recoveryId){
  return jdbc.query(RECOVERY+" WHERE site_id=? AND id=?",RECOVERY_MAPPER,siteId,recoveryId).stream().findFirst();
 }
 @Transactional
 public DeadlockRecovery openRecovery(long siteId,long vehicleId,long beneficiaryId,long wantedNodeId,
                                      List<Long> chainVehicleIds,String stopCommandId,String reason,String requestedBy){
  if(givingWay(siteId,vehicleId)) throw new IllegalStateException("RECOVERY_ALREADY_OPEN");
  Long id=jdbc.queryForObject("INSERT INTO traffic.deadlock_recovery(site_id,vehicle_id,beneficiary_vehicle_id,wanted_node_id,"
   +"chain_vehicle_ids,status,reason,requested_by,stop_command_id) VALUES(?,?,?,?,?::bigint[],'STOPPING',?,?,?) RETURNING id",Long.class,
   siteId,vehicleId,beneficiaryId,wantedNodeId,arrayText(chainVehicleIds),reason.length()<=256?reason:reason.substring(0,256),
   requestedBy.length()<=128?requestedBy:requestedBy.substring(0,128),stopCommandId);
  return recovery(siteId,id).orElseThrow();
 }
 @Transactional
 public void recoveryStopSettled(long recoveryId){
  jdbc.update("UPDATE traffic.deadlock_recovery SET stop_settled_at=clock_timestamp() WHERE id=? AND stop_settled_at IS NULL",recoveryId);
 }
 @Transactional
 public DeadlockRecovery releaseForRecovery(long recoveryId,long positionNodeId){
  var rec=jdbc.query(RECOVERY+" WHERE id=? FOR UPDATE",RECOVERY_MAPPER,recoveryId).stream().findFirst()
   .orElseThrow(()->new IllegalStateException("RECOVERY_NOT_FOUND"));
  if(!DeadlockRecovery.STOPPING.equals(rec.status())||!rec.open()) return rec;
  var before=heldBy(rec.siteId(),rec.vehicleId()).stream().map(NodeHold::nodeId).toList();
  releaseAhead(rec.siteId(),rec.vehicleId(),positionNodeId,"DEADLOCK_RECOVERY");
  var after=new HashSet<>(heldBy(rec.siteId(),rec.vehicleId()).stream().map(NodeHold::nodeId).toList());
  var released=before.stream().filter(n->!after.contains(n)).toList();
  jdbc.update("UPDATE traffic.deadlock_recovery SET status='YIELDING',released_node_ids=?::bigint[],released_at=clock_timestamp()"
   +" WHERE id=?",arrayText(released),recoveryId);
  return recovery(rec.siteId(),recoveryId).orElseThrow();
 }
 @Transactional
 public List<DeadlockRecovery> settleYielding(long siteId){
  var out=new ArrayList<DeadlockRecovery>();
  for(var rec:jdbc.query(RECOVERY+" WHERE site_id=? AND status='YIELDING' AND finished_at IS NULL FOR UPDATE",RECOVERY_MAPPER,siteId)){
   long node=rec.wantedNodeId();
   var takers=new HashSet<Long>(jdbc.queryForList("SELECT vehicle_id FROM traffic.node_reservation WHERE site_id=? AND node_id=?"
    +" AND released_at IS NULL",Long.class,siteId,node));
   takers.addAll(jdbc.queryForList("SELECT vehicle_id FROM fleet.vehicle_state WHERE site_id=? AND node_id=?",Long.class,siteId,node));
   takers.remove(rec.vehicleId());
   String done=null;
   if(takers.contains(rec.beneficiaryVehicleId())) done="BENEFICIARY_TOOK_NODE";
   else if(!takers.isEmpty()) done="NODE_TAKEN_BY_OTHER";
   else if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM traffic.reservation_wait WHERE site_id=?"
    +" AND vehicle_id=? AND wanted_node_id=?)",Boolean.class,siteId,rec.beneficiaryVehicleId(),node))) done="BENEFICIARY_NOT_WAITING";
   if(done!=null) out.add(finishRecovery(rec.id(),DeadlockRecovery.DONE,done));
  }
  return out;
 }
 @Transactional
 public DeadlockRecovery finishRecovery(long recoveryId,String status,String finishReason){
  if(!DeadlockRecovery.DONE.equals(status)&&!DeadlockRecovery.ABANDONED.equals(status))
   throw new IllegalArgumentException("finish status must be DONE or ABANDONED");
  var reason=finishReason==null?null:finishReason.length()<=64?finishReason:finishReason.substring(0,64);
  jdbc.update("UPDATE traffic.deadlock_recovery SET status=?,finished_at=clock_timestamp(),finish_reason=?"
   +" WHERE id=? AND finished_at IS NULL",status,reason,recoveryId);
  return jdbc.query(RECOVERY+" WHERE id=?",RECOVERY_MAPPER,recoveryId).stream().findFirst()
   .orElseThrow(()->new IllegalStateException("RECOVERY_NOT_FOUND"));
 }
 /**
  * Recomputes who is actually in the way instead of trusting what was recorded when the request
  * was refused: the blocker may have moved on, and reporting a deadlock that has already cleared
  * would send an operator after a vehicle that is no longer stuck.
  */
 private Map<Long,Wait> liveWaits(long siteId){
  // In the way means holding the node or standing on it, the same two things a request is refused for.
  var rows=jdbc.query("SELECT w.vehicle_id,w.wanted_node_id,h.vehicle_id FROM traffic.reservation_wait w"
   +" JOIN (SELECT node_id,vehicle_id FROM traffic.node_reservation WHERE released_at IS NULL"
   +"  UNION SELECT node_id,vehicle_id FROM fleet.vehicle_state WHERE node_id IS NOT NULL) h"
   +"  ON h.node_id=w.wanted_node_id"
   +" WHERE w.site_id=? AND h.vehicle_id<>w.vehicle_id ORDER BY h.vehicle_id",
   (r,n)->new Wait(r.getLong(1),r.getLong(2),r.getLong(3)),siteId);
  var out=new LinkedHashMap<Long,Wait>();
  for(var wait:rows) out.put(wait.vehicleId(),wait);
  return out;
 }

 /**
  * One movement authority never covers the same node twice: a run that loops back would release
  * the earlier visit as soon as the vehicle reported the later one.
  */
 private static List<Long> untilFirstRepeat(List<Long> route,int cap){
  var out=new ArrayList<Long>(); var seen=new HashSet<Long>();
  for(Long node:route){ if(node==null||!seen.add(node)||out.size()>=cap) break; out.add(node); }
  return out;
 }
 private int nodeCount(long siteId){
  var rows=jdbc.query("SELECT node_count FROM platform.site_operation WHERE site_id=?",(r,n)->r.getInt(1),siteId);
  return rows.isEmpty()?1:Math.max(rows.getFirst(),1);
 }
 /** Ascending node id, always, so two overlapping requests queue instead of trapping each other. */
 private void lockInAscendingIdOrder(long siteId,List<Long> nodeIds){
  for(Long id:nodeIds.stream().sorted().toList())
   jdbc.query("SELECT id FROM topology.node WHERE site_id=? AND id=? FOR UPDATE",(r,n)->r.getLong(1),siteId,id);
 }
 private Map<Long,Long> holdersOf(List<Long> nodeIds){
  var out=new HashMap<Long,Long>();
  for(Long id:nodeIds)
   jdbc.query("SELECT vehicle_id FROM traffic.node_reservation WHERE node_id=? AND released_at IS NULL",
    (r,n)->r.getLong(1),id).forEach(v->out.put(id,v));
  return out;
 }

 /**
  * Other vehicles last reported standing on these nodes. A report that has gone quiet still counts:
  * not knowing where a vehicle went is no reason to believe it left.
  */
 private Map<Long,Long> standingOn(long siteId,List<Long> nodeIds,long vehicleId){
  var out=new HashMap<Long,Long>();
  for(Long id:nodeIds)
   jdbc.query("SELECT vehicle_id FROM fleet.vehicle_state WHERE site_id=? AND node_id=? AND vehicle_id<>?"
    +" ORDER BY vehicle_id LIMIT 1",(r,n)->r.getLong(1),siteId,id,vehicleId).forEach(v->out.put(id,v));
  return out;
 }

 private record NodeAttributes(boolean enabled,boolean stopNode,boolean mergePoint,Long zoneId){}
 /** A node missing from this revision comes back absent, which the caller treats as not available. */
 private Map<Long,NodeAttributes> attributesOf(long siteId,long revisionId,List<Long> nodeIds){
  var out=new HashMap<Long,NodeAttributes>();
  for(Long id:nodeIds)
   jdbc.query("SELECT n.enabled,n.stop_node,n.merge_point,z.id FROM topology.node n"
    +" LEFT JOIN topology.zone_node zn ON zn.node_id=n.id"
    +" LEFT JOIN topology.zone z ON z.id=zn.zone_id AND z.enabled"
    +" WHERE n.site_id=? AND n.revision_id=? AND n.id=?",
    (r,n)->new NodeAttributes(r.getBoolean(1),r.getBoolean(2),r.getBoolean(3),r.getObject(4)==null?null:r.getLong(4)),
    siteId,revisionId,id).forEach(a->out.put(id,a));
  return out;
 }
 private String mergeControl(long siteId){
  var rows=jdbc.query("SELECT merge_control FROM platform.site_operation WHERE site_id=?",(r,n)->r.getString(1),siteId);
  return rows.isEmpty()?"CONTROLLER":rows.getFirst();
 }
 /**
  * Entering a full zone is refused. The zone row is locked first so two vehicles cannot both see
  * the last free place and both take it. Every request takes node locks before zone locks, so the
  * two kinds of lock cannot form a cycle between requests.
  */
 private String zoneRefusal(long siteId,long zoneId,long vehicleId){
  var capacity=jdbc.query("SELECT capacity FROM topology.zone WHERE id=? AND site_id=? FOR UPDATE",
   (r,n)->r.getInt(1),zoneId,siteId);
  if(capacity.isEmpty()) return null;
  return vehiclesIn(siteId,zoneId,vehicleId).size()>=capacity.getFirst()?"ZONE_FULL":null;
 }
 private boolean holdsInZone(long vehicleId,long zoneId){
  Integer count=jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation r"
   +" JOIN topology.zone_node zn ON zn.node_id=r.node_id"
   +" WHERE r.vehicle_id=? AND r.released_at IS NULL AND zn.zone_id=?",Integer.class,vehicleId,zoneId);
  return count!=null&&count>0;
 }
 /**
  * Who counts against the capacity: a vehicle standing in the zone, or one already permitted to
  * occupy any of its nodes. Counting only positions would let two vehicles be admitted before
  * either had arrived.
  */
 private List<Long> vehiclesIn(long siteId,long zoneId,Long except){
  return jdbc.query("SELECT DISTINCT v FROM ("
   +" SELECT r.vehicle_id v FROM traffic.node_reservation r JOIN topology.zone_node zn ON zn.node_id=r.node_id"
   +"  WHERE r.site_id=? AND r.released_at IS NULL AND zn.zone_id=?"
   +" UNION SELECT s.vehicle_id FROM fleet.vehicle_state s JOIN topology.zone_node zn ON zn.node_id=s.node_id"
   +"  WHERE s.site_id=? AND zn.zone_id=?) x WHERE v<>COALESCE(?,-1) ORDER BY v",
   (r,n)->r.getLong(1),siteId,zoneId,siteId,zoneId,except);
 }
 /**
  * First come first served at a merge point: another vehicle that was refused this node before this
  * one asked, and is still asking, goes first. A wait nobody has renewed within the site's
  * freshness window is no longer anyone waiting.
  */
 private Long earlierWaiter(long siteId,long nodeId,long vehicleId){
  var rows=jdbc.query("SELECT w.vehicle_id FROM traffic.reservation_wait w"
   +" JOIN platform.site_operation o ON o.site_id=w.site_id"
   +" WHERE w.site_id=? AND w.wanted_node_id=? AND w.vehicle_id<>?"
   +"  AND w.seen_at>=now()-make_interval(secs=>o.position_stale_seconds)"
   +"  AND w.since<COALESCE((SELECT m.since FROM traffic.reservation_wait m"
   +"   WHERE m.site_id=? AND m.vehicle_id=? AND m.wanted_node_id=?),'infinity'::timestamptz)"
   +" ORDER BY w.since,w.vehicle_id LIMIT 1",
   (r,n)->r.getLong(1),siteId,nodeId,vehicleId,siteId,vehicleId,nodeId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private int nextSequence(long siteId,long vehicleId){
  Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(sequence),0) FROM traffic.node_reservation"
   +" WHERE site_id=? AND vehicle_id=? AND released_at IS NULL",Integer.class,siteId,vehicleId);
  return (max==null?0:max)+1;
 }
 private static String truncate(String reason){
  return reason==null?null:reason.length()<=64?reason:reason.substring(0,64);
 }
 private static final String SELECT="SELECT id,site_id,revision_id,node_id,vehicle_id,order_id,sequence,granted_at"
  +" FROM traffic.node_reservation";
 private static final RowMapper<NodeHold> MAPPER=(r,n)->new NodeHold(r.getLong(1),r.getLong(2),r.getLong(3),
  r.getLong(4),r.getLong(5),r.getObject(6)==null?null:r.getLong(6),r.getInt(7),r.getObject(8,OffsetDateTime.class));
}
