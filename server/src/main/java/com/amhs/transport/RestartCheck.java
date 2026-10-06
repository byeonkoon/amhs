package com.amhs.transport;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.CommandStatus;
import com.amhs.integration.DeviceCommand;
import com.amhs.traffic.NodeHold;
import com.amhs.traffic.TrafficControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * The comparison a site needs after the server starts again.
 *
 * <p>The ledger says what was in progress when the server went down; nothing says what the vehicles
 * did while nobody was listening. Until the two have been compared the site does not progress -- no
 * assignment, no new road, no move of the controller's own, nothing sent -- while vehicle reports,
 * receipts and an operator's own recovery work go on. A site with nothing in progress has nothing to
 * compare and carries on at once.
 *
 * <p>What has to be settled before an operator may confirm: every vehicle the work involves has
 * reported since the start (silence proves nothing); where it reports is on the road it holds; and no
 * command is left whose outcome the restart lost. An operator may close such a command only once its
 * vehicle has been seen standing still since the start, and it is recorded as possibly received.
 * Vehicles taken out of service count as isolated. Orders already waiting for a person (a carrier
 * question, a cancel the vehicle has not confirmed) are listed and stay as they are.
 *
 * <p>On confirmation, commands written before the start and never sent are withdrawn (a stop is kept:
 * stopping is always safe), and a vehicle seen standing still since the start gives back the road it
 * holds ahead of where it stands, so its work is given road again from where it actually is. A
 * vehicle still moving keeps its road. Nothing is released on a timer or on silence.
 */
@Service
public class RestartCheck {
 private final JdbcTemplate jdbc; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox; private final TransportLedger ledger;

 public RestartCheck(JdbcTemplate jdbc,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger){
  this.jdbc=jdbc; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox; this.ledger=ledger;
 }

 /**
  * One thing found. {@code blocking}: confirmation is refused while it is there.
  * Kinds: VEHICLE_SILENT, POSITION_UNKNOWN, VEHICLE_OFF_ROAD, COMMAND_IN_DOUBT (blocking);
  * VEHICLE_CONFIRMED, VEHICLE_MOVING, VEHICLE_ISOLATED, COMMAND_UNSENT, ORDER_WAITS_FOR_OPERATOR.
  */
 public record Item(String kind,boolean blocking,Long vehicleId,Long orderId,String commandId,Long nodeId,String detail){}
 public record Finished(long id,OffsetDateTime startedAt,OffsetDateTime finishedAt,String outcome,String finishedBy,
                        String reason,Integer releasedNodes,Integer withdrawnCommands,int settledCommands){}
 /** Where the site stands: pending or not, since when, what was found, and how the last check ended. */
 public record State(boolean pending,Long checkId,OffsetDateTime since,List<Item> items,boolean confirmable,Finished last){}

 /** True while the site waits for its comparison; the site does not progress meanwhile. */
 @Transactional(readOnly=true)
 public boolean pending(long siteId){
  return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transport.restart_check"
   +" WHERE site_id=? AND finished_at IS NULL)",Boolean.class,siteId));
 }

 /**
  * The server has started: a check opens (or, if one was still open, starts over from now, since
  * only reports from this run count). With nothing in progress it ends at once.
  */
 @Transactional
 public State begin(long siteId){
  int moved=jdbc.update("UPDATE transport.restart_check SET started_at=clock_timestamp(),settled_commands=0"
   +" WHERE site_id=? AND finished_at IS NULL",siteId);
  if(moved==0) jdbc.update("INSERT INTO transport.restart_check(site_id) VALUES(?)",siteId);
  if(!inProgress(siteId))
   jdbc.update("UPDATE transport.restart_check SET finished_at=clock_timestamp(),outcome='NOTHING_IN_PROGRESS',"
    +"finished_by='system' WHERE site_id=? AND finished_at IS NULL",siteId);
  return state(siteId);
 }

 @Transactional(readOnly=true)
 public State state(long siteId){
  var open=jdbc.query("SELECT id,started_at FROM transport.restart_check WHERE site_id=? AND finished_at IS NULL",
   (r,n)->new Object[]{r.getLong(1),r.getObject(2,OffsetDateTime.class)},siteId).stream().findFirst();
  var last=jdbc.query("SELECT id,started_at,finished_at,outcome,finished_by,finish_reason,released_nodes,withdrawn_commands,"
   +"settled_commands FROM transport.restart_check WHERE site_id=? AND finished_at IS NOT NULL ORDER BY finished_at DESC,id DESC LIMIT 1",
   (r,n)->new Finished(r.getLong(1),r.getObject(2,OffsetDateTime.class),r.getObject(3,OffsetDateTime.class),r.getString(4),
    r.getString(5),r.getString(6),r.getObject(7,Integer.class),r.getObject(8,Integer.class),r.getInt(9)),siteId)
   .stream().findFirst().orElse(null);
  if(open.isEmpty()) return new State(false,null,null,List.of(),false,last);
  long id=(Long)open.get()[0]; var since=(OffsetDateTime)open.get()[1];
  var items=items(siteId,since);
  return new State(true,id,since,items,items.stream().noneMatch(Item::blocking),last);
 }

 /** The operator closes one command the restart left in doubt, on the evidence of its vehicle standing still since. */
 @Transactional
 public State closeInDoubt(long siteId,String commandId,String reason){
  requireReason(reason);
  var since=lockOpen(siteId);
  var command=commands(siteId).stream().filter(c->c.commandId().equals(commandId)).findFirst()
   .orElseThrow(()->new TransportRejected("COMMAND_NOT_FOUND","no unsettled command with that id",false));
  if(!command.status().inDoubt()||!command.createdAt().isBefore(since))
   throw new TransportRejected("COMMAND_NOT_LOST_IN_RESTART","only a command claimed or sent before the start can be closed here",false);
  var vehicle=command.vehicleId()==null?null:fleet.find(siteId,ledger.activeRevision(siteId),command.vehicleId()).orElse(null);
  if(vehicle==null||!standingSince(vehicle,since))
   throw new TransportRejected("VEHICLE_NOT_SEEN_STANDING","the vehicle has not been seen standing still since the start",true);
  if(outbox.closeInDoubtAfterRestart(commandId,reason))
   jdbc.update("UPDATE transport.restart_check SET settled_commands=settled_commands+1 WHERE site_id=? AND finished_at IS NULL",siteId);
  return state(siteId);
 }

 /** The operator confirms the comparison. Refused while anything blocking is left. */
 @Transactional
 public State confirm(long siteId,String reason,String actor){
  requireReason(reason);
  var since=lockOpen(siteId);
  var items=items(siteId,since);
  long blocking=items.stream().filter(Item::blocking).count();
  if(blocking>0)
   throw new TransportRejected("RESTART_CHECK_UNRESOLVED",blocking+" item(s) still unresolved",true);
  // Decided before the start, on a state nobody has confirmed since; its work will be given road
  // again from where the vehicle actually is. A stop is kept.
  int withdrawn=0;
  for(var c:commands(siteId)){
   if(c.createdAt().isBefore(since)&&c.status().retryable()&&!"STOP".equals(c.commandType())){
    outbox.withdrawIfUnsent(c.commandId(),"RESTART_CHECK");
    withdrawn++;
   }
  }
  int released=0;
  long revisionId=ledger.activeRevision(siteId);
  for(var v:fleet.snapshot(siteId,revisionId)){
   if(!standingSince(v,since)||!outbox.unresolvedForVehicle(siteId,v.id()).isEmpty()) continue;
   var holds=traffic.heldBy(siteId,v.id());
   if(holds.size()>1) released+=traffic.releaseAhead(siteId,v.id(),v.nodeId(),"RESTART_CHECK");
  }
  jdbc.update("UPDATE transport.restart_check SET finished_at=clock_timestamp(),outcome='CONFIRMED',finished_by=?,"
   +"finish_reason=?,released_nodes=?,withdrawn_commands=? WHERE site_id=? AND finished_at IS NULL",
   actor.length()<=128?actor:actor.substring(0,128),reason.length()<=256?reason:reason.substring(0,256),released,withdrawn,siteId);
  return state(siteId);
 }

 private OffsetDateTime lockOpen(long siteId){
  return jdbc.query("SELECT started_at FROM transport.restart_check WHERE site_id=? AND finished_at IS NULL FOR UPDATE",
   (r,n)->r.getObject(1,OffsetDateTime.class),siteId).stream().findFirst()
   .orElseThrow(()->new TransportRejected("NO_RESTART_CHECK","no restart check is open",true));
 }
 private static void requireReason(String reason){
  if(reason==null||reason.isBlank()) throw new TransportRejected("REASON_REQUIRED","say what was checked (X-Amhs-Reason)",false);
 }

 /** Seen standing still on a node, online, in a report made since the start. */
 private static boolean standingSince(VehicleSnapshot v,OffsetDateTime since){
  return v.online()&&v.nodeId()!=null&&!v.betweenNodes()&&v.idle()&&"IDLE".equals(v.effectiveStatus())
   &&v.reportedAt()!=null&&v.reportedAt().isAfter(since);
 }

 private List<DeviceCommand> commands(long siteId){
  var out=new ArrayList<DeviceCommand>();
  for(var v:fleet.snapshot(siteId,ledger.activeRevision(siteId))) out.addAll(outbox.unresolvedForVehicle(siteId,v.id()));
  return out;
 }

 /** Anything the ledger says was under way: work, moves, charges, unsettled commands, road beyond where a vehicle stands. */
 private boolean inProgress(long siteId){
  if(!ledger.activeAssignments(siteId).isEmpty()||!ledger.openRelocations(siteId).isEmpty()
   ||!ledger.chargingVehicleIds(siteId).isEmpty()||!commands(siteId).isEmpty()
   ||!traffic.recoveries(siteId,0).isEmpty()) return true;
  var where=new HashMap<Long,Long>();
  for(var v:fleet.snapshot(siteId,ledger.activeRevision(siteId))) if(v.nodeId()!=null) where.put(v.id(),v.nodeId());
  for(NodeHold h:traffic.held(siteId)) if(!Long.valueOf(h.nodeId()).equals(where.get(h.vehicleId()))) return true;
  return false;
 }

 private List<Item> items(long siteId,OffsetDateTime since){
  long revisionId=ledger.activeRevision(siteId);
  var vehicles=fleet.snapshot(siteId,revisionId);
  var holds=new HashMap<Long,List<Long>>();
  for(NodeHold h:traffic.held(siteId)) holds.computeIfAbsent(h.vehicleId(),k->new ArrayList<>()).add(h.nodeId());
  var involved=new TreeSet<Long>(holds.keySet());
  for(var a:ledger.activeAssignments(siteId)) involved.add(a.vehicleId());
  for(var r:ledger.openRelocations(siteId)) involved.add(r.vehicleId());
  involved.addAll(ledger.chargingVehicleIds(siteId));
  var commands=commands(siteId);
  for(var c:commands) if(c.vehicleId()!=null) involved.add(c.vehicleId());

  var out=new ArrayList<Item>();
  for(var v:vehicles){
   if(!involved.contains(v.id())) continue;
   var held=holds.getOrDefault(v.id(),List.of());
   if(v.outOfService()||!v.enabled()){
    out.add(new Item("VEHICLE_ISOLATED",false,v.id(),null,null,v.nodeId(),v.outOfService()?"OUT_OF_SERVICE":"NOT_IN_USE"));
   }else if(v.reportedAt()==null||!v.reportedAt().isAfter(since)){
    out.add(new Item("VEHICLE_SILENT",true,v.id(),null,null,v.nodeId(),null));
   }else if(v.nodeId()==null){
    out.add(new Item("POSITION_UNKNOWN",true,v.id(),null,null,null,null));
   }else if(!held.isEmpty()&&!held.contains(v.nodeId())){
    out.add(new Item("VEHICLE_OFF_ROAD",true,v.id(),null,null,v.nodeId(),"HOLDS "+held));
   }else if(!standingSince(v,since)){
    out.add(new Item("VEHICLE_MOVING",false,v.id(),null,null,v.nodeId(),v.effectiveStatus()));
   }else{
    out.add(new Item("VEHICLE_CONFIRMED",false,v.id(),null,null,v.nodeId(),held.size()>1?"ROAD_AHEAD_GIVEN_BACK_ON_CONFIRM":null));
   }
  }
  for(var c:commands){
   if(!c.createdAt().isBefore(since)) continue;
   if(c.status().inDoubt()) out.add(new Item("COMMAND_IN_DOUBT",true,c.vehicleId(),c.orderId(),c.commandId(),null,c.commandType()+" "+c.status()));
   else if(c.status()==CommandStatus.PENDING||c.status()==CommandStatus.FAILED)
    out.add(new Item("COMMAND_UNSENT",false,c.vehicleId(),c.orderId(),c.commandId(),null,c.commandType()));
  }
  for(var o:ledger.listActive(siteId,500))
   if(o.status()==OrderStatus.RECOVERY_REQUIRED||o.status()==OrderStatus.CANCEL_REQUESTED)
    out.add(new Item("ORDER_WAITS_FOR_OPERATOR",false,null,o.id(),null,null,o.status().name()));
  return out;
 }
}
