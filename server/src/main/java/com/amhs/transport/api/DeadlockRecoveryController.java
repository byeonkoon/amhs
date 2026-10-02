package com.amhs.transport.api;
import com.amhs.platform.Principal;
import com.amhs.platform.RequestReason;
import com.amhs.traffic.DeadlockPlan;
import com.amhs.traffic.DeadlockRecovery;
import com.amhs.transport.DeadlockRecoveries;
import com.amhs.transport.TransportRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.List;
import java.util.Map;

/**
 * Deadlocks as an operator handles them: each chain with the vehicles that could give way, the
 * recoveries under way, choosing a vehicle to give way (a reason is required), and calling it off.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}")
class DeadlockRecoveryController {
 private final DeadlockRecoveries recoveries;
 DeadlockRecoveryController(DeadlockRecoveries recoveries){ this.recoveries=recoveries; }

 @GetMapping("/deadlock-plans")
 List<DeadlockPlan> plans(@PathVariable long siteId){ return recoveries.plans(siteId); }

 /** Open recoveries and the twenty most recently finished. */
 @GetMapping("/deadlock-recoveries")
 List<DeadlockRecovery> list(@PathVariable long siteId){ return recoveries.list(siteId); }

 @PostMapping("/deadlock-recoveries")
 ResponseEntity<DeadlockRecovery> start(@PathVariable long siteId,@RequestBody Map<String,Object> body,
   @RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  if(!(body.get("vehicleId") instanceof Number vehicle))
   throw new TransportRejected("VEHICLE_REQUIRED","vehicleId is required",false);
  var started=recoveries.start(siteId,vehicle.longValue(),RequestReason.fromHeader(reason),actor());
  return ResponseEntity.status(HttpStatus.CREATED).body(started);
 }

 @PostMapping("/deadlock-recoveries/{recoveryId}/close")
 DeadlockRecovery close(@PathVariable long siteId,@PathVariable long recoveryId){
  return recoveries.close(siteId,recoveryId);
 }

 @ExceptionHandler(TransportRejected.class)
 ResponseEntity<Map<String,String>> rejected(TransportRejected ex){
  var status="RECOVERY_NOT_FOUND".equals(ex.code())||"VEHICLE_NOT_FOUND".equals(ex.code())?HttpStatus.NOT_FOUND
   :ex.isConflict()?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY;
  return ResponseEntity.status(status).body(Map.of("code",ex.code(),"message",ex.getMessage()));
 }

 private static String actor(){
  var attributes=RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(Principal.REQUEST_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  return principal instanceof Principal p?p.code():"api";
 }
}
