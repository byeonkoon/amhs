package com.amhs.platform.api;
import com.amhs.platform.OperationOptionConfiguration;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/**
 * Stop the fleet, or start it again.
 *
 * <p>Deliberately not under {@code /configuration/}: this is not a setting that outlives a shift,
 * it is the thing somebody reaches for when something is wrong on the floor. So it takes an
 * operator rather than an administrator, and it does not ask for the site to be shut down first --
 * a stop that required a shutdown would be no stop at all.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/execution")
class ExecutionController {
 private final OperationOptionConfiguration options;
 ExecutionController(OperationOptionConfiguration options){ this.options=options; }

 @GetMapping Map<String,Boolean> state(@PathVariable long siteId){
  return Map.of("running",options.sequenceRunning(siteId));
 }
 @PutMapping Map<String,Boolean> set(@PathVariable long siteId,@RequestBody Map<String,Object> body){
  if(!(body.get("running") instanceof Boolean running))
   throw new com.amhs.platform.ConfigurationException(422,"RUNNING_REQUIRED");
  return Map.of("running",options.setSequenceRunning(siteId,running));
 }
}
