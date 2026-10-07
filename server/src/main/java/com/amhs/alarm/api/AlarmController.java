package com.amhs.alarm.api;
import com.amhs.alarm.Alarm;
import com.amhs.alarm.SiteAlarms;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** What is wrong at a site now. A read, open like the other reads: seeing is level 1. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/alarms")
class AlarmController {
 private final SiteAlarms alarms;
 AlarmController(SiteAlarms alarms){ this.alarms=alarms; }

 @GetMapping
 List<Alarm> alarms(@PathVariable long siteId){ return alarms.alarms(siteId); }

 /** When each alarm was seen true and gone, newest first; optionally about one vehicle or one order. */
 @GetMapping("/history")
 List<SiteAlarms.Occurrence> history(@PathVariable long siteId,@RequestParam(required=false) Long vehicleId,
                                     @RequestParam(required=false) Long orderId,@RequestParam(defaultValue="100") int limit){
  return alarms.history(siteId,vehicleId,orderId,limit);
 }

 @GetMapping("/count")
 SiteAlarms.Count count(@PathVariable long siteId){ return alarms.count(siteId); }

 /**
  * Records that somebody has looked at an alarm. It does not clear it.
  *
  * <p>The alarm comes back in the reply, still in the list, now saying who saw it and what they
  * said. A control that removed the line would be a control for hiding faults.
  */
 @PutMapping("/{alarmKey}/seen")
 Alarm acknowledge(@PathVariable long siteId,@PathVariable String alarmKey,
                   @RequestBody java.util.Map<String,Object> body){
  var reason=body.get("reason");
  return alarms.acknowledge(siteId,alarmKey,reason==null?null:String.valueOf(reason),actor());
 }

 /** Who the access gate let through; a request that never passed it is just "api". */
 private static String actor(){
  var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(com.amhs.platform.Principal.REQUEST_ATTRIBUTE,
   org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
  return principal instanceof com.amhs.platform.Principal p?p.code():"api";
 }

 @ExceptionHandler(IllegalArgumentException.class)
 org.springframework.http.ResponseEntity<java.util.Map<String,String>> rejected(IllegalArgumentException ex){
  var status="ALARM_NOT_LIVE".equals(ex.getMessage())
   ?org.springframework.http.HttpStatus.CONFLICT
   :org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
  return org.springframework.http.ResponseEntity.status(status)
   .body(java.util.Map.of("code",String.valueOf(ex.getMessage())));
 }
}
