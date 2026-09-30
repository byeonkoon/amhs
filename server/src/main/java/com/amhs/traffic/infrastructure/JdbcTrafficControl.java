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

  lockInAscendingIdOrder(siteId,wanted);
  var holders=holdersOf(wanted);
  var usable=enabledAmong(siteId,revisionId,wanted);

  var granted=new ArrayList<Long>();
  Long blockedNode=null,blockedBy=null; String reason=null;
  int sequence=nextSequence(siteId,vehicleId);
  for(Long node:wanted){
   Long holder=holders.get(node);
   boolean mine=holder!=null&&holder==vehicleId;
   if(holder!=null&&!mine){
    blockedNode=node; blockedBy=holder;
    // The very first node is where the vehicle already is. Someone else holding it means the
    // permission map and the floor disagree, which is worse than congestion and is named so.
    reason=granted.isEmpty()?"POSITION_HELD_BY_OTHER_VEHICLE":"NODE_HELD_BY_OTHER_VEHICLE";
    break;
   }
   if(!usable.contains(node)){ blockedNode=node; reason="NODE_NOT_AVAILABLE"; break; }
   if(!mine){
    jdbc.update("INSERT INTO traffic.node_reservation(site_id,revision_id,node_id,vehicle_id,order_id,sequence)"
     +" VALUES(?,?,?,?,?,?)",siteId,revisionId,node,vehicleId,orderId,sequence++);
   }
   granted.add(node);
  }
  boolean complete=blockedNode==null;
  if(complete) jdbc.update("DELETE FROM traffic.reservation_wait WHERE site_id=? AND vehicle_id=?",siteId,vehicleId);
  else jdbc.update("INSERT INTO traffic.reservation_wait(site_id,vehicle_id,wanted_node_id,blocked_by_vehicle_id)"
   +" VALUES(?,?,?,?) ON CONFLICT(site_id,vehicle_id) DO UPDATE SET"
   +" wanted_node_id=EXCLUDED.wanted_node_id,blocked_by_vehicle_id=EXCLUDED.blocked_by_vehicle_id,since=now()",
   siteId,vehicleId,blockedNode,blockedBy);
  return new ReservationGrant(vehicleId,granted,complete,blockedNode,blockedBy,reason);
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

 private record Wait(long vehicleId,long wantedNodeId,Long blockerId){}
 /**
  * Recomputes who is actually in the way instead of trusting what was recorded when the request
  * was refused: the blocker may have moved on, and reporting a deadlock that has already cleared
  * would send an operator after a vehicle that is no longer stuck.
  */
 private Map<Long,Wait> liveWaits(long siteId){
  var rows=jdbc.query("SELECT w.vehicle_id,w.wanted_node_id,r.vehicle_id FROM traffic.reservation_wait w"
   +" JOIN traffic.node_reservation r ON r.node_id=w.wanted_node_id AND r.released_at IS NULL"
   +" WHERE w.site_id=? AND r.vehicle_id<>w.vehicle_id",
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
 private Set<Long> enabledAmong(long siteId,long revisionId,List<Long> nodeIds){
  var out=new HashSet<Long>();
  for(Long id:nodeIds)
   out.addAll(jdbc.query("SELECT id FROM topology.node WHERE site_id=? AND revision_id=? AND id=? AND enabled",
    (r,n)->r.getLong(1),siteId,revisionId,id));
  return out;
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
