package com.amhs.transport.api;
import com.amhs.transport.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import com.amhs.platform.Principal;
import java.util.*;
@RestController
@RequestMapping("/api/v1/sites/{siteId}/transport-orders")
class TransportOrderController {
 private final TransportOrders orders; private final TransportAssignment assignment;
 TransportOrderController(TransportOrders orders,TransportAssignment assignment){this.orders=orders;this.assignment=assignment;}

 /** 201 for a new order, 200 when the same key and payload are replayed. */
 @PostMapping
 ResponseEntity<TransportOrder> create(@PathVariable long siteId,
  @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
  @RequestHeader(value="X-Amhs-Request-Source",defaultValue="api") String requestSource,
  @RequestHeader(value="X-Amhs-Reason",required=false) String reason,
  @RequestBody Map<String,Object> body){
  var request=new OrderRequest(requestSource,
   idempotencyKey!=null?idempotencyKey:str(body.get("idempotencyKey")),
   str(body.get("sourcePortCode")),str(body.get("destinationPortCode")),
   str(body.get("carrierCode")),intOrNull(body.get("priority")));
  var accepted=orders.accept(siteId,request,actor(),traceId());
  return ResponseEntity.status(accepted.created()?HttpStatus.CREATED:HttpStatus.OK).body(accepted.order());
 }
 /**
  * {@code scope=active} lists the work still going on, {@code scope=finished} the history.
  * Without a scope, or with a single {@code status}, it lists as before.
  */
 @GetMapping
 List<TransportOrder> list(@PathVariable long siteId,
  @RequestParam(required=false) String status,@RequestParam(required=false) String scope,
  @RequestParam(defaultValue="50") int limit){
  if("active".equals(scope)) return orders.listActive(siteId,limit);
  if("finished".equals(scope)) return orders.listFinished(siteId,limit);
  return orders.list(siteId,status==null?null:parseStatus(status),limit);
 }
 @GetMapping("/{orderId}")
 TransportOrder get(@PathVariable long siteId,@PathVariable long orderId){
  return orders.find(siteId,orderId).orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
 }
 /** Which vehicle each order is holding right now. */
 /** Transfer reports that arrived after their order ended and disagree with it, not yet dealt with. */
 @GetMapping("/late-evidence")
 List<TransportLedger.LateEvidence> lateEvidence(@PathVariable long siteId){ return orders.openLateEvidence(siteId); }
 @PostMapping("/late-evidence/{id}/resolve")
 Map<String,Object> resolveLate(@PathVariable long siteId,@PathVariable long id,
  @RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  orders.resolveLateEvidence(siteId,id,com.amhs.platform.RequestReason.fromHeader(reason),actor());
  return Map.of("id",id,"resolved",true);
 }
 @GetMapping("/assignments")
 List<AssignmentView> assignments(@PathVariable long siteId){return assignment.active(siteId);}

 @GetMapping("/{orderId}/events")
 List<OrderEventView> events(@PathVariable long siteId,@PathVariable long orderId){return orders.events(siteId,orderId);}

 /** Requesting a cancel is not the same as the work stopping; after pickup the device must confirm. */
 @PostMapping("/{orderId}/cancel")
 TransportOrder cancel(@PathVariable long siteId,@PathVariable long orderId,
  @RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  return orders.cancel(siteId,orderId,com.amhs.platform.RequestReason.fromHeader(reason),actor(),traceId());
 }

 /**
  * Ends a recovery with where the carrier was found: SOURCE cancels the order, DESTINATION completes
  * it. The reason is required because it is the only record of how that was established.
  */
 @PostMapping("/{orderId}/recovery")
 TransportOrder recover(@PathVariable long siteId,@PathVariable long orderId,@RequestBody Map<String,Object> body){
  CarrierLocation location;
  try{ location=CarrierLocation.valueOf(String.valueOf(body.get("carrierLocation"))); }
  catch(IllegalArgumentException e){ throw new TransportRejected("RECOVERY_LOCATION_REQUIRED","unknown carrier location",false); }
  return orders.resolveRecovery(siteId,orderId,location,str(body.get("reason")),actor(),traceId());
 }

 /**
  * Moves an order on, with the evidence the move rests on. This is how a device adapter or the
  * simulator reports progress: finishing a pickup or a drop needs evidence of where the carrier
  * went, trying one again needs evidence that it did not move, and a person's confirmation needs
  * an identified person.
  */
 @PostMapping("/{orderId}/transitions")
 TransportOrder transition(@PathVariable long siteId,@PathVariable long orderId,@RequestBody Map<String,Object> body){
  var target=parseStatus(str(body.get("status")));
  var evidence=body.get("evidence") instanceof Map<?,?> m?m:Map.of();
  TransitionEvidence.Kind kind;
  try{ kind=evidence.get("kind")==null?TransitionEvidence.Kind.SYSTEM:TransitionEvidence.Kind.valueOf(String.valueOf(evidence.get("kind"))); }
  catch(IllegalArgumentException e){ throw new TransportRejected("UNKNOWN_EVIDENCE_KIND",String.valueOf(evidence.get("kind")),false); }
  CarrierLocation location;
  try{ location=evidence.get("carrierLocation")==null?null:CarrierLocation.valueOf(String.valueOf(evidence.get("carrierLocation"))); }
  catch(IllegalArgumentException e){ throw new TransportRejected("UNKNOWN_CARRIER_LOCATION",String.valueOf(evidence.get("carrierLocation")),false); }
  return orders.advance(siteId,orderId,target,str(body.get("reason")),actor(),traceId(),
   new TransitionEvidence(kind,str(evidence.get("commandId")),location));
 }

 /**
  * Holds a vehicle for this order. Repeating the request returns the vehicle already held
  * rather than assigning a second one.
  */
 @PostMapping("/{orderId}/assignment")
 ResponseEntity<TransportAssignment.Assigned> assign(@PathVariable long siteId,@PathVariable long orderId){
  var assigned=assignment.assign(siteId,orderId,actor(),traceId());
  return ResponseEntity.status(assigned.created()?HttpStatus.CREATED:HttpStatus.OK).body(assigned);
 }

 @ExceptionHandler(TransportRejected.class)
 ResponseEntity<Map<String,String>> rejected(TransportRejected ex){
  var status=ex.isConflict()?HttpStatus.CONFLICT
   :"ORDER_NOT_FOUND".equals(ex.code())?HttpStatus.NOT_FOUND:HttpStatus.UNPROCESSABLE_ENTITY;
  return ResponseEntity.status(status).body(Map.of("code",ex.code(),"message",ex.getMessage(),"traceId",traceId()));
 }
 private static OrderStatus parseStatus(String value){
  try{return OrderStatus.valueOf(value);}catch(IllegalArgumentException e){throw new TransportRejected("UNKNOWN_STATUS",value,false);}
 }
 private static String str(Object v){return v==null?null:String.valueOf(v);}
 private static Integer intOrNull(Object v){
  if(v==null)return null;
  if(v instanceof Number n)return n.intValue();
  try{return Integer.valueOf(String.valueOf(v));}catch(NumberFormatException e){throw new TransportRejected("PRIORITY_NOT_A_NUMBER","priority must be a number",false);}
 }
 /** Who the access gate let through; a request that never passed it is just "api". */
 private static String actor(){
  var attributes=RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(Principal.REQUEST_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  return principal instanceof Principal p?p.code():"api";
 }
 private static String traceId(){return UUID.randomUUID().toString();}
}
