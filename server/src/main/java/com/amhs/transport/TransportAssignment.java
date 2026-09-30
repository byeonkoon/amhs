package com.amhs.transport;
import com.amhs.dispatch.DispatchEvaluator;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.topology.TopologyQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 private final CommandOutbox outbox; private final ObjectMapper json;
 public TransportAssignment(TransportLedger ledger,TopologyQuery topology,FleetState fleet,
                            CommandOutbox outbox,ObjectMapper json){
  this.ledger=ledger; this.topology=topology; this.fleet=fleet;
  this.evaluator=new DispatchEvaluator(); this.outbox=outbox; this.json=json;
 }

 public record Assigned(TransportOrder order,AssignmentView assignment,String commandId,boolean created){}

 @Transactional
 public Assigned assign(long siteId,long orderId,String actorCode,String traceId){
  var order=ledger.lock(siteId,orderId)
   .orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
  // A repeated request for an order that already holds a vehicle returns what it holds instead
  // of assigning a second one or emitting a second movement command.
  var held=ledger.activeAssignment(siteId,orderId);
  if(held.isPresent()) return new Assigned(order,held.get(),null,false);
  if(order.status()!=OrderStatus.QUEUED)
   throw new TransportRejected("ORDER_NOT_QUEUED",order.status()+" cannot be assigned",false);

  var map=topology.getMap(siteId,order.revisionId());
  // A vehicle already holding a job is not a candidate. The unique index still settles a race,
  // but filtering here lets a second order pick a different vehicle instead of just failing.
  var busy=ledger.assignedVehicleIds(siteId);
  var candidates=fleet.snapshot(siteId,order.revisionId()).stream()
   .filter(v->!busy.contains(v.id())).map(TransportAssignment::candidate).toList();
  var evaluated=evaluator.evaluate(map,order.sourcePortId(),order.destinationPortId(),candidates);
  // Waiting for a vehicle is not a rejection of the request, so these stay retryable.
  var proposal=evaluated.proposal()
   .orElseThrow(()->new TransportRejected(evaluated.reason(),"no vehicle could be assigned",true));

  fleet.lockForAssignment(siteId,List.of(proposal.vehicleId()));
  // The proposal was made before the lock, so eligibility is checked again underneath it.
  var vehicle=fleet.find(siteId,order.revisionId(),proposal.vehicleId())
   .orElseThrow(()->new TransportRejected("VEHICLE_NOT_FOUND","assigned vehicle disappeared",true));
  if(!eligible(vehicle))
   throw new TransportRejected("VEHICLE_NO_LONGER_ELIGIBLE","vehicle changed state before it was held",true);

  long assignmentId=ledger.assign(orderId,proposal.vehicleId());
  var assigned=ledger.transition(siteId,orderId,OrderStatus.ASSIGNED,
   "assigned to "+vehicle.code(),actorCode,traceId);
  String commandId="order-"+orderId+"-assignment-"+assignmentId+"-MOVE";
  outbox.enqueue(orderId,commandId,"MOVE",payload(proposal,vehicle));
  return new Assigned(assigned,ledger.activeAssignment(siteId,orderId).orElseThrow(),commandId,true);
 }

 private static DispatchEvaluator.Candidate candidate(VehicleSnapshot v){
  return new DispatchEvaluator.Candidate(v.id(),v.vType(),v.nodeId(),v.enabled(),v.assignmentEnabled(),
   v.online(),v.remote(),v.idle());
 }
 private static boolean eligible(VehicleSnapshot v){
  return v.enabled()&&v.assignmentEnabled()&&v.online()&&v.remote()&&v.idle()&&v.nodeId()!=null;
 }

 /**
  * Carries the ordered segments of both legs so the device adapter and, later, the segment
  * reservation work from the route that was actually costed rather than replanning their own.
  */
 private String payload(DispatchEvaluator.Proposal proposal,VehicleSnapshot vehicle){
  var body=new LinkedHashMap<String,Object>();
  body.put("vehicleId",vehicle.id()); body.put("vehicleCode",vehicle.code());
  body.put("approachNodeIds",proposal.toSource().nodeIds());
  body.put("approachEdgeIds",proposal.toSource().edgeIds());
  body.put("deliveryNodeIds",proposal.toDestination().nodeIds());
  body.put("deliveryEdgeIds",proposal.toDestination().edgeIds());
  body.put("estimatedSeconds",proposal.cost());
  try{ return json.writeValueAsString(body); }
  catch(JsonProcessingException e){ throw new IllegalStateException("command payload could not be written",e); }
 }
}
