package com.amhs.transport;
import com.amhs.dispatch.DispatchEvaluator;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.ReservationGrant;
import com.amhs.traffic.TrafficControl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Holds one vehicle for one order.
 *
 * <p>The decision and everything it implies are written together: the order state, the
 * assignment, its event and the outbound command share one transaction, so a vehicle is never
 * held for a job the ledger does not record, and a command never exists for a decision that was
 * rolled back. Two callers racing for the same vehicle are settled by the database rather than
 * by the order in which they happened to read.
 */
@Service
public class TransportAssignment {
 private final TransportLedger ledger; private final TopologyQuery topology;
 private final FleetState fleet; private final DispatchEvaluator evaluator;
 private final CommandOutbox outbox; private final ObjectMapper json; private final TrafficControl traffic;
 private final ZoneDetour detour; private final OperationOptionConfiguration operations;
 private final com.amhs.equipment.SimulatedEquipment equipment;
 public TransportAssignment(TransportLedger ledger,TopologyQuery topology,FleetState fleet,
                            CommandOutbox outbox,ObjectMapper json,TrafficControl traffic,ZoneDetour detour,
                            OperationOptionConfiguration operations,com.amhs.equipment.SimulatedEquipment equipment){
  this.ledger=ledger; this.topology=topology; this.fleet=fleet;
  this.evaluator=new DispatchEvaluator(); this.outbox=outbox; this.json=json; this.traffic=traffic;
  this.detour=detour; this.operations=operations; this.equipment=equipment;
 }

 public record Assigned(TransportOrder order,AssignmentView assignment,String commandId,boolean created){}

 /** Every vehicle currently held at this site, so a screen can name the holder of each order. */
 public List<AssignmentView> active(long siteId){ return ledger.activeAssignments(siteId); }

 @Transactional
 public Assigned assign(long siteId,long orderId,String actorCode,String traceId){
  // Nothing is dispatched while the site is stopped, or on a state nobody has compared with the
  // vehicles since the server started.
  String halted=operations.whyNotProgressing(siteId);
  if(halted!=null) throw new TransportRejected(halted,"the site is not progressing",true);
  var order=ledger.lock(siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  // A repeated request for an order that already holds a vehicle returns what it holds instead
  // of assigning a second one or emitting a second movement command.
  var held=ledger.activeAssignment(siteId,orderId);
  if(held.isPresent()) return new Assigned(order,held.get(),null,false);
  if(order.status()!=OrderStatus.QUEUED)
   throw new TransportRejected("ORDER_NOT_QUEUED",order.status()+" cannot be assigned",false);
  // One transfer at a port at a time. The ports are claimed first, so two assignments cannot both
  // find them free; the order then waits until the other order has picked up or finished.
  ledger.lockPorts(List.of(order.sourcePortId(),order.destinationPortId()));
  try { equipment.requireAvailable(siteId,order.sourcePortId(),order.destinationPortId()); }
  catch(com.amhs.equipment.SimulatedEquipment.Prohibited e){throw new TransportRejected(e.getMessage(),e.getMessage(),true);}
  if(ledger.portInUse(siteId,orderId,order.sourcePortId(),order.destinationPortId()))
   throw new TransportRejected("PORT_IN_USE","another order is using the source or destination port",true);

  var map=topology.getMap(siteId,order.revisionId());
  // A vehicle already holding a job is not a candidate. The unique index still settles a race,
  // but filtering here lets a second order pick a different vehicle instead of just failing. One
  // being moved out of a zone is busy too: its own move would cross the new one.
  var busy=new java.util.HashSet<>(ledger.assignedVehicleIds(siteId));
  busy.addAll(ledger.relocatingVehicleIds(siteId));
  // A vehicle with a carrier on board cannot pick up another one, and one that must charge is not
  // given work (an ordinary charge request gives way to work).
  busy.addAll(ledger.chargingVehicleIds(siteId));
  var snapshot=fleet.snapshot(siteId,order.revisionId());
  var levels=ledger.chargeLevels(siteId);
  var free=snapshot.stream()
   .filter(v->!busy.contains(v.id())&&!v.carrierPresent()&&!mustCharge(siteId,v,levels)).toList();
  // A state nobody has confirmed lately is not a state to dispatch on: the vehicle that said it
  // was idle and online a day ago may be anywhere now. The same line is drawn again under the lock.
  var cutoff=staleBefore(siteId);
  var current=free.stream().filter(v->v.reportedSince(cutoff)).toList();
  var evaluated=evaluator.evaluate(map,order.sourcePortId(),order.destinationPortId(),
   current.stream().map(TransportAssignment::candidate).toList());
  // Waiting for a vehicle is not a rejection of the request, so these stay retryable.
  var proposal=evaluated.proposal()
   .orElseThrow(()->new TransportRejected(current.isEmpty()&&!free.isEmpty()?"VEHICLE_STATE_STALE":evaluated.reason(),
    "no vehicle could be assigned",true));

  fleet.lockForAssignment(siteId,List.of(proposal.vehicleId()));
  // A move out of a zone takes the same lock, so what it committed is visible from here on.
  if(ledger.relocatingVehicleIds(siteId).contains(proposal.vehicleId()))
   throw new TransportRejected("VEHICLE_RELOCATING","vehicle is being moved out of a zone",true);
  // The proposal was made before the lock, so eligibility is checked again underneath it.
  var vehicle=fleet.find(siteId,order.revisionId(),proposal.vehicleId())
   .orElseThrow(()->new TransportRejected("VEHICLE_NOT_FOUND","assigned vehicle disappeared",true));
  // Under the lock: the state of charge and any charge as they are now, not as a sweep left them.
  if(!eligible(vehicle)||!vehicle.reportedSince(cutoff)||mustCharge(siteId,vehicle,ledger.chargeLevels(siteId))
    ||ledger.chargingVehicleIds(siteId).contains(vehicle.id()))
   throw new TransportRejected("VEHICLE_NO_LONGER_ELIGIBLE","vehicle changed state before it was held",true);
  // The route was costed from where the vehicle stood when it was proposed. If it has moved since,
  // that route starts somewhere it no longer is.
  if(!vehicle.nodeId().equals(proposal.toSource().nodeIds().getFirst()))
   throw new TransportRejected("VEHICLE_MOVED","vehicle moved after the route was planned",true);

  var route=fullRoute(proposal);
  // The pickup is the last node of the approach leg; nothing past it is authorised until the
  // pickup has been reported.
  int pickupIndex=proposal.toSource().nodeIds().size()-1;
  long assignmentId=ledger.assign(orderId,proposal.vehicleId(),route,pickupIndex);
  var assigned=ledger.transition(siteId,orderId,OrderStatus.ASSIGNED,
   "assigned to "+vehicle.code(),actorCode,traceId);

  // Holding the vehicle is not permission to move it. The command may only carry the run traffic
  // actually granted, so a congested aisle produces a shorter movement rather than a vehicle
  // driving into nodes somebody else is standing on. Before the pickup that run ends at the source.
  var grant=traffic.request(siteId,order.revisionId(),proposal.vehicleId(),orderId,route.subList(0,pickupIndex+1));
  // Heading into a bay that is already full: go round the loop instead of stopping at the branch.
  grant=detour.aroundFullZone(siteId,order.revisionId(),proposal.vehicleId(),orderId,assigned.status(),0,grant);
  if(grant.stalled())
   // The vehicle is held and the order is assigned; it simply cannot start yet. Emitting no
   // command is the point, and the recorded wait says what it is waiting for.
   return new Assigned(assigned,ledger.activeAssignment(siteId,orderId).orElseThrow(),null,true);

  String commandId="order-"+orderId+"-assignment-"+assignmentId+"-MOVE";
  outbox.enqueue(orderId,proposal.vehicleId(),commandId,"MOVE",payload(proposal,vehicle,grant));
  return new Assigned(assigned,ledger.activeAssignment(siteId,orderId).orElseThrow(),commandId,true);
 }

 /**
  * How many vehicles of each kind could be given work right now: in use, taking work, controllable,
  * idle on a node, fresh, empty, and holding no order or move. Zero for a kind means no order of
  * that kind can be assigned this time round, whatever its place in the queue.
  */
 public java.util.Map<com.amhs.platform.VehicleType,Integer> freeVehicles(long siteId){
  var busy=new java.util.HashSet<>(ledger.assignedVehicleIds(siteId));
  busy.addAll(ledger.relocatingVehicleIds(siteId));
  busy.addAll(ledger.chargingVehicleIds(siteId));
  var cutoff=staleBefore(siteId);
  var levels=ledger.chargeLevels(siteId);
  var out=new java.util.EnumMap<com.amhs.platform.VehicleType,Integer>(com.amhs.platform.VehicleType.class);
  for(var v:fleet.snapshot(siteId,ledger.activeRevision(siteId)))
   if(!busy.contains(v.id())&&eligible(v)&&v.reportedSince(cutoff)&&!mustCharge(siteId,v,levels)) out.merge(v.vType(),1,Integer::sum);
  return out;
 }

 /**
  * Must charge before any work: it is at the must-charge level and not yet charged past release, or
  * an AGV that charges reports, right now, less than the critical charge with no carrier on board.
  * The current reading counts even before the charging pass has recorded the level.
  */
 private boolean mustCharge(long siteId,VehicleSnapshot v,java.util.Map<Long,Integer> levels){
  var s=operations.get(siteId).settings();
  boolean charges=v.vType()==com.amhs.platform.VehicleType.AGV&&v.chargingPolicy()!=null&&!"DISABLED".equals(v.chargingPolicy());
  if(!charges) return false;
  Double b=v.batteryPct();
  if(levels.getOrDefault(v.id(),0)>=2&&(b==null||b<=s.chargeReleasePct())) return true;
  return b!=null&&b<s.chargeCriticalPct()&&!v.carrierPresent();
 }

 private static DispatchEvaluator.Candidate candidate(VehicleSnapshot v){
  return new DispatchEvaluator.Candidate(v.id(),v.vType(),v.nodeId(),v.enabled(),v.assignmentEnabled(),
   v.online(),v.remote(),v.idle(),v.outOfService());
 }
 /**
  * The whole path the vehicle will travel, starting where it stands. The two legs share the
  * source node, so the join is dropped rather than asked for twice.
  */
 private static List<Long> fullRoute(DispatchEvaluator.Proposal proposal){
  var route=new java.util.ArrayList<>(proposal.toSource().nodeIds());
  var delivery=proposal.toDestination().nodeIds();
  route.addAll(delivery.subList(Math.min(1,delivery.size()),delivery.size()));
  return route;
 }
 private OffsetDateTime staleBefore(long siteId){
  return OffsetDateTime.now().minusSeconds(operations.get(siteId).settings().positionStaleSeconds());
 }
 /**
  * Whether this vehicle may be given work.
  *
  * <p>Out of service is checked here rather than only at the screen, because taking a vehicle out
  * has to mean it stops being used -- a vehicle somebody decided is not fit to run, that the next
  * dispatch picks up anyway, was never taken out of anything.
  */
 private static boolean eligible(VehicleSnapshot v){
  return v.enabled()&&v.assignmentEnabled()&&!v.outOfService()
   &&v.online()&&v.remote()&&v.idle()&&v.nodeId()!=null&&!v.carrierPresent();
 }

 /**
  * Carries the ordered segments of both legs so the device adapter and, later, the segment
  * reservation work from the route that was actually costed rather than replanning their own.
  */
 private String payload(DispatchEvaluator.Proposal proposal,VehicleSnapshot vehicle,ReservationGrant grant){
  var body=new LinkedHashMap<String,Object>();
  body.put("vehicleId",vehicle.id()); body.put("vehicleCode",vehicle.code());
  body.put("approachNodeIds",proposal.toSource().nodeIds());
  body.put("approachEdgeIds",proposal.toSource().edgeIds());
  body.put("deliveryNodeIds",proposal.toDestination().nodeIds());
  body.put("deliveryEdgeIds",proposal.toDestination().edgeIds());
  // What the vehicle may occupy, which is the part of the plan it is allowed to act on.
  body.put("authorizedNodeIds",grant.nodeIds());
  body.put("authorityComplete",grant.complete());
  body.put("estimatedSeconds",proposal.cost());
  try{ return json.writeValueAsString(body); }
  catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }
}
