package com.amhs.statistics.api;
import com.amhs.statistics.DailyStatistics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

/** Daily normal-completion rate, cancel reasons and abnormal endings for a range of site days. */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/statistics")
class StatisticsController {
 private final DailyStatistics statistics;
 StatisticsController(DailyStatistics statistics){ this.statistics=statistics; }

 @GetMapping("/daily")
 DailyStatistics.Report daily(@PathVariable long siteId,@RequestParam LocalDate from,@RequestParam LocalDate to,
                              @RequestParam(required=false) String zone){
  return statistics.daily(siteId,from,to,zone);
 }
 @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class})
 ResponseEntity<Map<String,String>> bad(RuntimeException e){
  return ResponseEntity.unprocessableEntity().body(Map.of("code",e.getMessage()==null?"INVALID_RANGE":e.getMessage()));
 }
}
