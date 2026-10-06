package com.amhs.transport.api;
import com.amhs.platform.Principal;
import com.amhs.platform.RequestReason;
import com.amhs.transport.RestartCheck;
import com.amhs.transport.TransportRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.Map;

/**
 * The comparison after a server start: what was found, an operator closing a command the restart
 * left in doubt, and the operator confirming. Both changes need a reason and are audited.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/restart-check")
class RestartCheckController {
 private final RestartCheck check;
 RestartCheckController(RestartCheck check){ this.check=check; }

 @GetMapping
 RestartCheck.State state(@PathVariable long siteId){ return check.state(siteId); }

 @PostMapping("/confirm")
 RestartCheck.State confirm(@PathVariable long siteId,@RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  return check.confirm(siteId,RequestReason.fromHeader(reason),actor());
 }

 @PostMapping("/commands/{commandId}/close")
 RestartCheck.State close(@PathVariable long siteId,@PathVariable String commandId,
   @RequestHeader(value="X-Amhs-Reason",required=false) String reason){
  return check.closeInDoubt(siteId,commandId,RequestReason.fromHeader(reason));
 }

 @ExceptionHandler(TransportRejected.class)
 ResponseEntity<Map<String,String>> rejected(TransportRejected ex){
  var status="COMMAND_NOT_FOUND".equals(ex.code())?HttpStatus.NOT_FOUND
   :ex.isConflict()?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY;
  return ResponseEntity.status(status).body(Map.of("code",ex.code(),"message",ex.getMessage()));
 }

 private static String actor(){
  var attributes=RequestContextHolder.getRequestAttributes();
  var principal=attributes==null?null:attributes.getAttribute(Principal.REQUEST_ATTRIBUTE,RequestAttributes.SCOPE_REQUEST);
  return principal instanceof Principal p?p.code():"api";
 }
}
