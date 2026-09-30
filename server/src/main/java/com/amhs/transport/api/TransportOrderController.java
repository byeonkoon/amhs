package com.amhs.transport.api;
import com.amhs.transport.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
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
 @GetMapping
 List<TransportOrder> list(@PathVariable long siteId,
  @RequestParam(required=false) String status,@RequestParam(defaultValue="50") int limit){
  return orders.list(siteId,status==null?null:parseStatus(status),limit);
 }
 @GetMapping("/{orderId}")
 TransportOrder get(@PathVariable long siteId,@PathVariable long orderId){
  return orders.find(siteId,orderId).orElseThrow(()->new TransportRejected("ORDER_NOT_FOUND","unknown order",false));
 }
 /** Which vehicle each order is holding right now. */
 @GetMapping("/assignments")
 List<AssignmentView> assignments(@PathVariable long siteId){return assignment.active(siteId);}

 @GetMapping("/{orderId}/events")
 List<OrderEventView> events(@PathVariable long siteId,@PathVariable long orderId){return orders.events(siteId,orderId);}

 /** Requesting a cancel is not the same as the work stopping; after pickup the device must confirm. */
 @PostMapping("/{orderId}/cancel")
 TransportOrder cancel(@PathVariable long siteId,@PathVariable long orderId,
  @RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  return orders.cancel(siteId,orderId,reason,actor(),traceId());
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
 // A16 resolves the principal in the access gate; wiring it through to the ledger is A16 follow-up.
 private static String actor(){return "api";}
 private static String traceId(){return UUID.randomUUID().toString();}
}
