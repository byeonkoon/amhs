package com.amhs.integration.api;
import com.amhs.integration.CommunicationConfigurationCheck;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/communication-check")
class CommunicationConfigurationCheckController {
 private final CommunicationConfigurationCheck check;
 CommunicationConfigurationCheckController(CommunicationConfigurationCheck check){this.check=check;}
 @GetMapping CommunicationConfigurationCheck.Report inspect(@PathVariable long siteId){return check.inspect(siteId);}
}
