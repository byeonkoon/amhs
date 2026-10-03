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

 @GetMapping("/count")
 SiteAlarms.Count count(@PathVariable long siteId){ return alarms.count(siteId); }
}
