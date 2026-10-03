package com.amhs.transport;
import com.amhs.platform.VehicleType;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.traffic.TrafficControl;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
/**
 * Transport order intake and lifecycle.
 *
 * <p>Acceptance is separated from execution: a created order is QUEUED and waits for dispatch.
 * A structural problem with the request is rejected here; waiting for a vehicle is not a rejection.
 */
@Service
public class TransportOrders {
 private final TransportLedger ledger; private final FleetState fleet; private final TrafficControl traffic;
 private final CommandOutbox outbox;
 private final com.amhs.equipment.SimulatedEquipment equipment;
 public TransportOrders(TransportLedger ledger,FleetState fleet,TrafficControl traffic,CommandOutbox outbox,
                        com.amhs.equipment.SimulatedEquipment equipment){
  this.ledger=ledger; this.fleet=fleet; this.traffic=traffic; this.outbox=outbox; this.equipment=equipment;
 }

 public record Accepted(TransportOrder order,boolean created){}

 @Transactional
 public Accepted accept(long siteId,OrderRequest request,String actorCode,String traceId){
  require(request.requestSource(),"REQUEST_SOURCE_REQUIRED");
  require(request.idempotencyKey(),"IDEMPOTENCY_KEY_REQUIRED");
  require(request.sourcePortCode(),"SOURCE_PORT_REQUIRED");
  require(request.destinationPortCode(),"DESTINATION_PORT_REQUIRED");
  int priority=request.priority()==null?50:request.priority();
  if(priority<0||priority>100)throw reject("PRIORITY_OUT_OF_RANGE","priority must be between 0 and 100");
  String fingerprint=fingerprint(request,priority);

  var existing=ledger.findByIdempotency(siteId,request.requestSource(),request.idempotencyKey());
  if(existing.isPresent()){
   // Same key with the same payload returns the original order; a different payload is a conflict.
   if(!fingerprint.equals(ledger.fingerprintOf(existing.get().id())))
    throw new TransportRejected("IDEMPOTENCY_KEY_CONFLICT","same key was used with a different request",true);
   return new Accepted(existing.get(),false);
  }

  long revisionId=ledger.activeRevision(siteId);
  var source=ledger.findPort(siteId,revisionId,request.sourcePortCode())
   .orElseThrow(()->reject("SOURCE_PORT_NOT_FOUND","unknown source port"));
  var destination=ledger.findPort(siteId,revisionId,request.destinationPortCode())
   .orElseThrow(()->reject("DESTINATION_PORT_NOT_FOUND","unknown destination port"));
  if(source.id()==destination.id())throw reject("SAME_SOURCE_AND_DESTINATION","source and destination must differ");
  // One vehicle cannot serve two different access types, so this is a handover job, not a single order.
  if(source.accessVehicleType()!=destination.accessVehicleType())
   throw reject("ACCESS_VEHICLE_TYPE_MISMATCH","source and destination require different vehicle types");
  try { equipment.requireAvailable(siteId,source.id(),destination.id()); }
  catch(com.amhs.equipment.SimulatedEquipment.Prohibited e){throw new TransportRejected(e.getMessage(),e.getMessage(),true);}

  // One carrier is in one place: a second order for a carrier still being moved is refused.
  if(request.carrierCode()!=null&&!request.carrierCode().isBlank()
   &&ledger.carrierInTransport(siteId,request.carrierCode()))
   throw new TransportRejected("CARRIER_IN_TRANSPORT","an unfinished order already carries this carrier",true);
  VehicleType type=source.accessVehicleType();
  var draft=new TransportOrder(0L,siteId,revisionId,request.requestSource(),request.idempotencyKey(),
   source.id(),destination.id(),type,request.carrierCode(),priority,
   OrderStatus.QUEUED,CarrierLocation.UNKNOWN,0L,null,null,null,null);
  return new Accepted(ledger.create(draft,fingerprint,actorCode,traceId),true);
 }

 /**
  * Cancel before pickup ends the order; after pickup it waits for the device to confirm.
  *
  * <p>A cancel before pickup also waits when a movement command has already left for the vehicle:
  * it may be driving on it, and ending the order would give its road away while it is still on it.
  * The order's commands are locked before deciding, the same rows the publisher claims, so a send
  * cannot slip in between the decision and the withdrawal of what has not left.
  */
 @Transactional
 public TransportOrder cancel(long siteId,long orderId,String reason,String actorCode,String traceId){
  var order=ledger.lock(siteId,orderId).orElseThrow(()->reject("ORDER_NOT_FOUND","unknown order"));
  var target=order.status().cancelTarget();
  if(target==null)throw new TransportRejected("ORDER_ALREADY_TERMINAL","order is already finished",true);
  var withdrawal=outbox.withdraw(orderId,"ORDER_CANCEL");
  if(target==OrderStatus.CANCELLED&&withdrawal.anyLeft()) target=OrderStatus.CANCEL_REQUESTED;
  return settle(siteId,orderId,ledger.transition(siteId,orderId,target,reason,actorCode,traceId));
 }

 @Transactional
 public TransportOrder advance(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId){
  return advance(siteId,orderId,target,reason,actorCode,traceId,TransitionEvidence.system());
 }

 /**
  * Moves the order on, resting on {@code evidence}. Finishing a pickup or a drop needs evidence of
  * where the carrier went; trying one again needs evidence that it did not move.
  */
 @Transactional
 public TransportOrder advance(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId,
                               TransitionEvidence evidence){
  var after=ledger.transition(siteId,orderId,target,reason,actorCode,traceId,evidence);
  // A stage in which the vehicle is not meant to drive takes back whatever movement has not left:
  // a command written for the previous stage would otherwise go out after the stage had ended.
  if(!after.status().movable()) outbox.withdraw(orderId,"ORDER_"+after.status());
  return settle(siteId,orderId,after);
 }

 /**
  * Ends a recovery once somebody has established where the carrier is. A carrier at the
  * destination completes the order, one back at the source cancels it. Still on the vehicle, or
  * not known, is not an answer: the order stays in recovery and keeps what it holds.
  */
 @Transactional
 public TransportOrder resolveRecovery(long siteId,long orderId,CarrierLocation location,String reason,
                                       String actorCode,String traceId){
  var order=ledger.lock(siteId,orderId).orElseThrow(()->reject("ORDER_NOT_FOUND","unknown order"));
  if(order.status()!=OrderStatus.RECOVERY_REQUIRED)
   throw new TransportRejected("ORDER_NOT_IN_RECOVERY",order.status()+" is not waiting for recovery",true);
  if(location!=CarrierLocation.SOURCE&&location!=CarrierLocation.DESTINATION)
   throw reject("RECOVERY_LOCATION_REQUIRED","the carrier must be found at the source or at the destination");
  if(reason==null||reason.isBlank())
   throw reject("RECOVERY_REASON_REQUIRED","say how the carrier location was established");
  var target=location==CarrierLocation.DESTINATION?OrderStatus.COMPLETED:OrderStatus.CANCELLED;
  outbox.withdraw(orderId,"ORDER_RECOVERED");
  // An operator's decision: it needs an identified person, and the location it states is written
  // with the transition that ends the order.
  return settle(siteId,orderId,ledger.transition(siteId,orderId,target,"RECOVERED: "+reason,actorCode,traceId,
   new TransitionEvidence(TransitionEvidence.Kind.OPERATOR_DECISION,null,location)));
 }

 /**
  * Gives back the road once the work is over.
  *
  * <p>The node the vehicle is standing on stays held: finishing a job does not move the vehicle
  * off the track, and handing that node to somebody else would route them into it. If nothing
  * has reported where the vehicle is, nothing is released at all, because every node in its run
  * is somewhere it might be.
  */
 private TransportOrder settle(long siteId,long orderId,TransportOrder after){
  if(!after.status().isTerminal())return after;
  // The ledger released the vehicle with the transition, so the holder is read from the event
  // that is still visible here rather than looked up again afterwards.
  var vehicleId=ledger.assignedVehicleOf(orderId);
  if(vehicleId==null)return after;
  var position=fleet.find(siteId,after.revisionId(),vehicleId).map(VehicleSnapshot::nodeId).orElse(null);
  traffic.releaseAhead(siteId,vehicleId,position,"ORDER_"+after.status());
  return after;
 }

 public Optional<TransportOrder> find(long siteId,long orderId){return ledger.find(siteId,orderId);}
 /** Waiting orders in dispatch order: priority, then acceptance time, then id. */
 public List<TransportOrder> dispatchQueue(long siteId,int limit){return ledger.dispatchQueue(siteId,Math.min(Math.max(limit,1),200));}
 /** The page that follows {@code after} in the same order. */
 public List<TransportOrder> dispatchQueue(long siteId,TransportOrder after,int limit){
  return ledger.dispatchQueue(siteId,after,Math.min(Math.max(limit,1),200));
 }
 public List<TransportOrder> list(long siteId,OrderStatus status,int limit){return ledger.list(siteId,status,Math.min(Math.max(limit,1),200));}
 /** Orders still being worked on. Finished ones belong to the history, not to the work list. */
 public List<TransportOrder> listActive(long siteId,int limit){return ledger.listActive(siteId,Math.min(Math.max(limit,1),500));}
 /** Finished orders, newest first, for the history. */
 public List<TransportOrder> listFinished(long siteId,int limit){return ledger.listFinished(siteId,Math.min(Math.max(limit,1),500));}
 public List<OrderEventView> events(long siteId,long orderId){
  if(ledger.find(siteId,orderId).isEmpty())throw reject("ORDER_NOT_FOUND","unknown order");
  return ledger.events(siteId,orderId);
 }

 private static void require(String value,String code){
  if(value==null||value.isBlank())throw new TransportRejected(code,code,false);
 }
 private static TransportRejected reject(String code,String message){return new TransportRejected(code,message,false);}
 static String fingerprint(OrderRequest r,int priority){
  String canonical=String.join("\u001f",nz(r.sourcePortCode()),nz(r.destinationPortCode()),nz(r.carrierCode()),String.valueOf(priority));
  try{
   var digest=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
   var out=new StringBuilder(64);
   for(byte b:digest)out.append(Character.forDigit((b>>4)&0xF,16)).append(Character.forDigit(b&0xF,16));
   return out.toString();
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
 }
 private static String nz(String v){return v==null?"":v;}
}
