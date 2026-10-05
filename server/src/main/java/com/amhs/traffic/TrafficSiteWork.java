package com.amhs.traffic;
import com.amhs.platform.SiteWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/**
 * Traffic's answer to "may this site's map change now": not while a vehicle is still on a road it
 * was granted.
 *
 * <p>A hold is permission to be somewhere on this map, and a vehicle standing still holds the node
 * it stands on -- permanently, because that is what stops anyone else being sent into it. So holds
 * on their own say nothing: on a quiet site every vehicle has one. Counting those made the map
 * impossible to change at all, which is the opposite of careful.
 *
 * <p>What matters is a hold the vehicle has not arrived at, or has not finished leaving: road
 * granted ahead of where it stands, a vehicle between two nodes, or a hold whose vehicle has not
 * said where it is. Each of those is a vehicle still moving on this map, and a map swapped under it
 * leaves it driving on track the new map does not have.
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
   +" WHERE r.site_id=? AND r.released_at IS NULL"
   +"  AND (s.node_id IS NULL OR s.node_id<>r.node_id OR s.x_mm IS NOT NULL)",Integer.class,siteId);
  return moving!=null&&moving>0?"ROADS_STILL_GRANTED":null;
 }
}
