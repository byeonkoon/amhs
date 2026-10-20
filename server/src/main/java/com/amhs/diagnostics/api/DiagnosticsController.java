package com.amhs.diagnostics.api;
import com.amhs.diagnostics.HostDiagnostics;
import org.springframework.web.bind.annotation.*;

/** The server host, network and vehicle links, as far as each can be known. A read, open like the others. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/diagnostics")
class DiagnosticsController {
 private final HostDiagnostics diagnostics; private final com.amhs.platform.ControlTiming timing;
 DiagnosticsController(HostDiagnostics diagnostics,com.amhs.platform.ControlTiming timing){
  this.diagnostics=diagnostics; this.timing=timing;
 }
 @GetMapping("/host")
 HostDiagnostics.Report host(@PathVariable long siteId){ return diagnostics.report(siteId); }

 /**
  * How the loops that drive the plant are keeping their periods.
  *
  * <p>Not per site, because the loops are not: one scheduler drives every site this server holds,
  * and a loop that is late is late for all of them.
  */
 @GetMapping("/timing")
 java.util.List<com.amhs.platform.ControlTiming.Loop> timing(@PathVariable long siteId){
  return timing.loops();
 }
}
