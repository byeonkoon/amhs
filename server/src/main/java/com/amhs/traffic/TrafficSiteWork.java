package com.amhs.traffic;
import com.amhs.platform.SiteWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/**
 * Traffic's answer to "may this site's map change now": not while a vehicle is moving on it.
 *
 * <p>A hold is permission to be somewhere on this map, and a vehicle standing still holds the node
 * it stands on -- permanently, because that is what stops anyone else being sent into it. It often
 * holds a little road ahead too, from the run it has just finished. Neither says anything is
 * happening: on a quiet site every vehicle has holds. Counting them made the map impossible to
 * change at all, which is the opposite of careful.
 *
 * <p>What a map change cannot survive is a vehicle in motion: one between two nodes, one that has
 * not said where it is, or one whose last word is old enough that where it is now is a guess.
 * Swapping the ground under any of those leaves a vehicle driving on track the new map does not
 * have. A vehicle standing still, by contrast, does not move because the map under it changed.
 *
 * <p>Holds belonging to a revision that is not in use are not counted either: permission to stand
 * on a map the site has stopped running is not permission to be anywhere.
 */
@Component
class TrafficSiteWork implements SiteWork {
 private final JdbcTemplate jdbc;
 TrafficSiteWork(JdbcTemplate jdbc){ this.jdbc=jdbc; }

 @Override public String whyBusy(long siteId){
  Integer moving=jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation r"
   +" JOIN topology.map_revision m ON m.site_id=r.site_id AND m.id=r.revision_id AND m.status='ACTIVE'"
   +" LEFT JOIN fleet.vehicle_state s ON s.site_id=r.site_id AND s.vehicle_id=r.vehicle_id"
   +"  AND s.revision_id=r.revision_id"
   +" LEFT JOIN platform.site_operation o ON o.site_id=r.site_id"
   +" WHERE r.site_id=? AND r.released_at IS NULL"
   +"  AND (s.node_id IS NULL OR s.x_mm IS NOT NULL OR s.y_mm IS NOT NULL"
   +"   OR s.online IS NOT TRUE OR s.idle IS NOT TRUE OR COALESCE(s.status,'IDLE')<>'IDLE'"
   +"   OR s.reported_at IS NULL OR s.source='INITIAL_CONFIGURATION'"
   +"   OR s.reported_at < now()-make_interval(secs => COALESCE(o.position_stale_seconds,30)))",
   Integer.class,siteId);
  return moving!=null&&moving>0?"ROADS_STILL_GRANTED":null;
 }
}
