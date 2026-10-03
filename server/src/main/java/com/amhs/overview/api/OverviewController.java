package com.amhs.overview.api;
import com.amhs.overview.SiteOverview;
import org.springframework.web.bind.annotation.*;

/** The site in one answer. A read, open like the other reads: seeing is level 1. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/overview")
class OverviewController {
 private final SiteOverview overview;
 OverviewController(SiteOverview overview){ this.overview=overview; }

 @GetMapping
 SiteOverview.Overview overview(@PathVariable long siteId,
                                @RequestParam(defaultValue="60") int windowMinutes){
  return overview.overview(siteId,windowMinutes);
 }
}
