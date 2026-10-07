package com.amhs.diagnostics.api;
import com.amhs.diagnostics.HostDiagnostics;
import org.springframework.web.bind.annotation.*;

/** The server host, network and vehicle links, as far as each can be known. A read, open like the others. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/diagnostics")
class DiagnosticsController {
 private final HostDiagnostics diagnostics;
 DiagnosticsController(HostDiagnostics diagnostics){ this.diagnostics=diagnostics; }
 @GetMapping("/host")
 HostDiagnostics.Report host(@PathVariable long siteId){ return diagnostics.report(siteId); }
}
