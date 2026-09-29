package com.amhs.transport;
import com.amhs.platform.VehicleType;
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
 private final TransportLedger ledger;
 public TransportOrders(TransportLedger ledger){this.ledger=ledger;}

 public record Accepted(TransportOrder order,boolean created){}

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

  VehicleType type=source.accessVehicleType();
  var draft=new TransportOrder(0L,siteId,revisionId,request.requestSource(),request.idempotencyKey(),
   source.id(),destination.id(),type,request.carrierCode(),priority,
   OrderStatus.QUEUED,CarrierLocation.UNKNOWN,0L,null,null,null,null);
  return new Accepted(ledger.create(draft,fingerprint,actorCode,traceId),true);
 }

 /** Cancel before pickup ends the order; after pickup it waits for the device to confirm. */
 public TransportOrder cancel(long siteId,long orderId,String reason,String actorCode,String traceId){
  var order=ledger.find(siteId,orderId).orElseThrow(()->reject("ORDER_NOT_FOUND","unknown order"));
  var target=order.status().cancelTarget();
  if(target==null)throw new TransportRejected("ORDER_ALREADY_TERMINAL","order is already finished",true);
  return ledger.transition(siteId,orderId,target,reason,actorCode,traceId);
 }

 public TransportOrder advance(long siteId,long orderId,OrderStatus target,String reason,String actorCode,String traceId){
  return ledger.transition(siteId,orderId,target,reason,actorCode,traceId);
 }

 public Optional<TransportOrder> find(long siteId,long orderId){return ledger.find(siteId,orderId);}
 public List<TransportOrder> list(long siteId,OrderStatus status,int limit){return ledger.list(siteId,status,Math.min(Math.max(limit,1),200));}
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
