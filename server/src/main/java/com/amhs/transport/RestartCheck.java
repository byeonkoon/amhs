package com.amhs.transport;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.CommandStatus;
import com.amhs.integration.DeviceCommand;
import com.amhs.platform.OperationOptionConfiguration;
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
 * assignment, no new road, no move, nothing sent, by whichever path it is asked for -- while vehicle
 * reports, receipts and an operator's own recovery work go on. A site with nothing in progress has
 * nothing to compare and carries on at once.
 *
 * <p>What has to be settled before an operator may confirm: every vehicle the work involves has
 * reported since the start and recently enough to count as now (silence proves nothing, and an hour-old
 * report is not a present standstill); where it reports is on the road it holds; no command is left
 * whose outcome the restart lost; and no vehicle carries a carrier its work says it should not have.
 * An operator may close a lost command only once its vehicle is seen standing still, now; it is
 * recorded as possibly received. Vehicles taken out of service count as isolated. Orders already
 * waiting for a person stay as they are.
 *
 * <p>Work whose carrier the vehicle's report contradicts -- a delivery with nothing aboard -- or that
 * was part-way through a handover when the server stopped is not resumed: confirming puts it into
 * recovery, where a person establishes where the carrier is.
 *
 * <p>On confirmation, under the vehicles' own locks so no report slips in between: commands written
 * before the start and never sent are withdrawn (a stop is kept), and a vehicle seen standing still now
 * gives back the road it holds ahead of where it stands, so its work is given road again from there.
 * A vehicle still moving keeps its road. Nothing is released on a timer or on silence.
 */
@Service
public class RestartCheck {
 private final JdbcTemplate jdbc; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox; private final TransportLedger ledger; private final TransportOrders orders;
 private final OperationOptionConfiguration operations;

 public RestartCheck(JdbcTemplate jdbc,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,TransportLedger ledger,
                     TransportOrders orders,OperationOptionConfiguration operations){
  this.jdbc=jdbc; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox; this.ledger=ledger;
  this.orders=orders; this.operations=operations;
 }

 /**
  * One thing found. {@code blocking}: confirmation is refused while it is there.
  * Blocking kinds: VEHICLE_SILENT, VEHICLE_REPORT_STALE, POSITION_UNKNOWN, VEHICLE_OFF_ROAD,
  * COMMAND_IN_DOUBT, CARRIER_UNEXPECTED. Others: VEHICLE_CONFIRMED, VEHICLE_MOVING, VEHICLE_ISOLATED,
  * COMMAND_UNSENT, CARRIER_MISMATCH and HANDOVER_INTERRUPTED (the order goes into recovery on
  * confirmation), ORDER_WAITS_FOR_OPERATOR.
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
  hold(siteId);
  if(!inProgress(siteId)){
   jdbc.update("UPDATE transport.restart_check SET finished_at=clock_timestamp(),outcome='NOTHING_IN_PROGRESS',"
    +"finished_by='system' WHERE site_id=? AND finished_at IS NULL",siteId);
   operations.markRestartCheck(siteId,null);
  }
  return state(siteId);
 }

 /**
  * Opens the check -- or starts an open one over from now -- and holds the site, without judging
  * whether anything was in progress. It is what {@link #begin} does first, and what is left when
  * the judgement itself cannot be made: a site nobody could compare is not a site that may carry on.
  */
 @Transactional
 public void hold(long siteId){
  int moved=jdbc.update("UPDATE transport.restart_check SET started_at=clock_timestamp(),settled_commands=0"
   +" WHERE site_id=? AND finished_at IS NULL",siteId);
  if(moved==0) jdbc.update("INSERT INTO transport.restart_check(site_id) VALUES(?)",siteId);
  var since=jdbc.queryForObject("SELECT started_at FROM transport.restart_check WHERE site_id=? AND finished_at IS NULL",
   OffsetDateTime.class,siteId);
  operations.markRestartCheck(siteId,since);
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

 /** The operator closes one command the restart left in doubt, on the evidence of its vehicle standing still now. */
 @Transactional
 public State closeInDoubt(long siteId,String commandId,String reason){
  requireReason(reason);
  var since=lockOpen(siteId);
  var command=commands(siteId,ledger.activeRevision(siteId)).stream().filter(c->c.commandId().equals(commandId)).findFirst()
   .orElseThrow(()->new TransportRejected("COMMAND_NOT_FOUND","no unsettled command with that id",false));
  if(!command.status().inDoubt()||!command.createdAt().isBefore(since))
   throw new TransportRejected("COMMAND_NOT_LOST_IN_RESTART","only a command claimed or sent before the start can be closed here",false);
  if(command.vehicleId()==null)
   throw new TransportRejected("VEHICLE_NOT_SEEN_STANDING","the command names no vehicle",true);
  // Under the vehicle's lock, so a report cannot change what is judged between reading and closing.
  fleet.lockForAssignment(siteId,List.of(command.vehicleId()));
  var vehicle=fleet.find(siteId,ledger.activeRevision(siteId),command.vehicleId()).orElse(null);
  if(vehicle==null||!standingNow(vehicle,since,cutoff(siteId)))
   throw new TransportRejected("VEHICLE_NOT_SEEN_STANDING","the vehicle has not been seen standing still since the start, recently",true);
  if(outbox.closeInDoubtAfterRestart(commandId,reason))
   jdbc.update("UPDATE transport.restart_check SET settled_commands=settled_commands+1 WHERE site_id=? AND finished_at IS NULL",siteId);
  return state(siteId);
 }

 /** The operator confirms the comparison. Refused while anything blocking is left. */
 @Transactional
 public State confirm(long siteId,String reason,String actor){
  requireReason(reason);
  var since=lockOpen(siteId);
  long revisionId=ledger.activeRevision(siteId);
  // Every vehicle is held still for the decision: what is judged is what is acted on.
  fleet.lockForAssignment(siteId,fleet.snapshot(siteId,revisionId).stream().map(VehicleSnapshot::id).toList());
  var items=items(siteId,since);
  long blocking=items.stream().filter(Item::blocking).count();
  if(blocking>0)
   throw new TransportRejected("RESTART_CHECK_UNRESOLVED",blocking+" item(s) still unresolved",true);
  // Work the vehicle's own report contradicts, or caught in a handover, is not resumed: where the
  // carrier is becomes a person's question.
  for(var item:items)
   if(("CARRIER_MISMATCH".equals(item.kind())||"HANDOVER_INTERRUPTED".equals(item.kind()))&&item.orderId()!=null)
    orders.advance(siteId,item.orderId(),OrderStatus.RECOVERY_REQUIRED,"RESTART_CHECK: "+item.kind(),actor,null);
  // Decided before the start, on a state nobody has confirmed since; its work will be given road
  // again from where the vehicle actually is. A stop is kept.
  int withdrawn=0;
  for(var c:commands(siteId,revisionId)){
   if(c.createdAt().isBefore(since)&&c.status().retryable()&&!"STOP".equals(c.commandType())){
    outbox.withdrawIfUnsent(c.commandId(),"RESTART_CHECK");
    withdrawn++;
   }
  }
  int released=0;
  var cutoff=cutoff(siteId);
  for(var v:fleet.snapshot(siteId,revisionId)){
   if(!standingNow(v,since,cutoff)||!outbox.unresolvedForVehicle(siteId,v.id()).isEmpty()) continue;
   if(traffic.heldBy(siteId,v.id()).size()>1) released+=traffic.releaseAhead(siteId,v.id(),v.nodeId(),"RESTART_CHECK");
  }
  jdbc.update("UPDATE transport.restart_check SET finished_at=clock_timestamp(),outcome='CONFIRMED',finished_by=?,"
   +"finish_reason=?,released_nodes=?,withdrawn_commands=? WHERE site_id=? AND finished_at IS NULL",
   actor.length()<=128?actor:actor.substring(0,128),reason.length()<=256?reason:reason.substring(0,256),released,withdrawn,siteId);
  operations.markRestartCheck(siteId,null);
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
 /** A report older than this says where the vehicle was, not where it is. */
 private OffsetDateTime cutoff(long siteId){
  return OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());
 }
 private static boolean reportedSince(VehicleSnapshot v,OffsetDateTime since){
  return v.reportedAt()!=null&&v.reportedAt().isAfter(since);
 }
 /** Seen standing still on a node, online, in a report made since the start and recent enough to be now. */
 private static boolean standingNow(VehicleSnapshot v,OffsetDateTime since,OffsetDateTime cutoff){
  return v.online()&&v.nodeId()!=null&&!v.betweenNodes()&&v.idle()&&"IDLE".equals(v.effectiveStatus())
   &&reportedSince(v,since)&&v.reportedSince(cutoff);
 }

 private List<DeviceCommand> commands(long siteId,long revisionId){
  var out=new ArrayList<DeviceCommand>();
  for(var v:fleet.snapshot(siteId,revisionId)) out.addAll(outbox.unresolvedForVehicle(siteId,v.id()));
  return out;
 }

 /** Anything the ledger says was under way: work, moves, charges, unsettled commands, road beyond where a vehicle stands. */
 private boolean inProgress(long siteId){
  // A site with no map in use was never commissioned: there is no track to stand on and nothing
  // could have been under way. Nothing to compare -- and the server still comes up.
  var active=ledger.activeRevisionIfAny(siteId);
  if(active.isEmpty()) return false;
  long revisionId=active.getAsLong();
  if(!ledger.activeAssignments(siteId).isEmpty()||!ledger.openRelocations(siteId).isEmpty()
   ||!ledger.chargingVehicleIds(siteId).isEmpty()||!commands(siteId,revisionId).isEmpty()
   ||!traffic.recoveries(siteId,0).isEmpty()) return true;
  var where=new HashMap<Long,Long>();
  for(var v:fleet.snapshot(siteId,revisionId)) if(v.nodeId()!=null) where.put(v.id(),v.nodeId());
  for(NodeHold h:traffic.held(siteId)) if(!Long.valueOf(h.nodeId()).equals(where.get(h.vehicleId()))) return true;
  return false;
 }

 private List<Item> items(long siteId,OffsetDateTime since){
  long revisionId=ledger.activeRevision(siteId);
  var cutoff=cutoff(siteId);
  var vehicles=fleet.snapshot(siteId,revisionId);
  var holds=new HashMap<Long,List<Long>>();
  for(NodeHold h:traffic.held(siteId)) holds.computeIfAbsent(h.vehicleId(),k->new ArrayList<>()).add(h.nodeId());
  var involved=new TreeSet<Long>(holds.keySet());
  var workOf=new HashMap<Long,Long>();
  for(var a:ledger.activeAssignments(siteId)){ involved.add(a.vehicleId()); workOf.put(a.vehicleId(),a.orderId()); }
  for(var r:ledger.openRelocations(siteId)) involved.add(r.vehicleId());
  involved.addAll(ledger.chargingVehicleIds(siteId));
  var commands=commands(siteId,revisionId);
  for(var c:commands) if(c.vehicleId()!=null) involved.add(c.vehicleId());

  var out=new ArrayList<Item>();
  for(var v:vehicles){
   if(!involved.contains(v.id())) continue;
   var held=holds.getOrDefault(v.id(),List.of());
   boolean heard=false;
   if(v.outOfService()||!v.enabled()){
    out.add(new Item("VEHICLE_ISOLATED",false,v.id(),null,null,v.nodeId(),v.outOfService()?"OUT_OF_SERVICE":"NOT_IN_USE"));
   }else if(!reportedSince(v,since)){
    out.add(new Item("VEHICLE_SILENT",true,v.id(),null,null,v.nodeId(),null));
   }else if(!v.reportedSince(cutoff)){
    out.add(new Item("VEHICLE_REPORT_STALE",true,v.id(),null,null,v.nodeId(),String.valueOf(v.reportedAt())));
   }else if(v.nodeId()==null){
    out.add(new Item("POSITION_UNKNOWN",true,v.id(),null,null,null,null));
   }else if(!held.isEmpty()&&!held.contains(v.nodeId())){
    out.add(new Item("VEHICLE_OFF_ROAD",true,v.id(),null,null,v.nodeId(),"HOLDS "+held));
   }else{
    heard=true;
    if(!standingNow(v,since,cutoff)) out.add(new Item("VEHICLE_MOVING",false,v.id(),null,null,v.nodeId(),v.effectiveStatus()));
    // Said only when confirming will really do it. A command that survives the confirmation -- a
    // stop, which is kept -- keeps the road with it, and a screen that promised otherwise would be
    // read as a fault when the road stayed.
    else out.add(new Item("VEHICLE_CONFIRMED",false,v.id(),null,null,v.nodeId(),
     held.size()<=1?null:keepsRoad(commands,v.id(),since)?"ROAD_AHEAD_KEPT_FOR_COMMAND":"ROAD_AHEAD_GIVEN_BACK_ON_CONFIRM"));
   }
   // What the vehicle now says it carries, against what its work says it should.
   Long orderId=workOf.get(v.id());
   if(heard&&orderId!=null){
    var order=ledger.find(siteId,orderId).orElse(null);
    if(order!=null) carrierItem(order,v).ifPresent(out::add);
   }
  }
  for(var c:commands){
   if(!c.createdAt().isBefore(since)) continue;
   if(c.status().inDoubt()) out.add(new Item("COMMAND_IN_DOUBT",true,c.vehicleId(),c.orderId(),c.commandId(),null,c.commandType()+" "+c.status()));
   else if(c.status()==CommandStatus.PENDING||c.status()==CommandStatus.FAILED)
    out.add(new Item("COMMAND_UNSENT",false,c.vehicleId(),c.orderId(),c.commandId(),null,c.commandType()));
  }
  // Every one of them, not a page of the newest: the one that has waited longest for a person is
  // the one a page leaves out.
  for(var status:List.of(OrderStatus.RECOVERY_REQUIRED,OrderStatus.CANCEL_REQUESTED))
   for(var o:ledger.inStatus(siteId,status))
    out.add(new Item("ORDER_WAITS_FOR_OPERATOR",false,null,o.id(),null,null,o.status().name()));
  return out;
 }

 /**
  * Whether this vehicle will still have a command outstanding after the confirmation, which is what
  * makes the confirmation leave its road alone. The mirror of what {@link #confirm} withdraws: what
  * was written before the start and may be repeated goes, except a stop, which is kept.
  */
 private static boolean keepsRoad(List<DeviceCommand> commands,long vehicleId,OffsetDateTime since){
  return commands.stream().filter(c->Long.valueOf(vehicleId).equals(c.vehicleId()))
   .anyMatch(c->!c.createdAt().isBefore(since)||!c.status().retryable()||"STOP".equals(c.commandType()));
 }

 /**
  * Before pickup the vehicle should carry nothing; between pickup and drop it should carry the
  * carrier. A handover the restart cut through is not known either way.
  */
 private static Optional<Item> carrierItem(TransportOrder order,VehicleSnapshot v){
  return switch(order.status()){
   case ASSIGNED,TO_SOURCE -> v.carrierPresent()
    ?Optional.of(new Item("CARRIER_UNEXPECTED",true,v.id(),order.id(),null,v.nodeId(),order.status().name()))
    :Optional.empty();
   case TO_DESTINATION -> v.carrierPresent()?Optional.empty()
    :Optional.of(new Item("CARRIER_MISMATCH",false,v.id(),order.id(),null,v.nodeId(),"TO_DESTINATION, NO CARRIER REPORTED"));
   case LOADING,UNLOADING -> Optional.of(new Item("HANDOVER_INTERRUPTED",false,v.id(),order.id(),null,v.nodeId(),order.status().name()));
   default -> Optional.empty();
  };
 }
}
