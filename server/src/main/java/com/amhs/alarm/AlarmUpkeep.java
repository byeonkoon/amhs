package com.amhs.alarm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Forgets acknowledgements of conditions that have ended.
 *
 * <p>An acknowledgement belongs to one occurrence. Kept past the end of the condition it would
 * silence the next one: the same fault returning would arrive already marked as seen, by somebody
 * who saw a different one, possibly weeks ago.
 *
 * <p>It runs on a timer rather than when the list is read, because reading must not write. The list
 * is open to anyone, and a read that changes the database is a read that can be made to change it.
 * The cost is a window -- a condition that clears and returns between two passes is still marked
 * seen -- and that is stated rather than hidden.
 *
 * <p>The sites to visit come from the acknowledgement table itself, so nothing here needs a list of
 * every site, and a site nobody has acknowledged anything at costs nothing to skip.
 */
@Component
class AlarmUpkeep {
 private final JdbcTemplate jdbc; private final SiteAlarms alarms;
 AlarmUpkeep(JdbcTemplate jdbc,SiteAlarms alarms){ this.jdbc=jdbc; this.alarms=alarms; }

 @Scheduled(fixedDelayString="${amhs.alarm.upkeep-ms:5000}")
 void retireWhatIsOver(){
  // Every site is looked at, so each alarm's coming and going is noted, with what anybody said about
  // it copied into the record before the acknowledgement is forgotten.
  for(Long siteId:jdbc.query("SELECT site_id FROM platform.site_operation ORDER BY site_id",(r,n)->r.getLong(1))){
   try{ alarms.observe(siteId); alarms.retireAcknowledgementsOfEndedConditions(siteId); }
   catch(RuntimeException e){
    // One site that cannot be read must not stop the others being tidied.
    SiteAlarms.log.debug("alarm upkeep skipped site {}: {}",siteId,e.getMessage());
   }
  }
 }
}
