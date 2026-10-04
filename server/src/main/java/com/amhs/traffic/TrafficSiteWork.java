package com.amhs.traffic;
import com.amhs.platform.SiteWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/**
 * Traffic's answer to "may this site's map change now": not while a vehicle holds a node.
 *
 * <p>A hold is permission to be somewhere on this map. Changing the map while one is out would
 * leave a vehicle permitted to stand on a node the new map does not have, and the new map's nodes
 * free for somebody else to be sent into.
 */
@Component
class TrafficSiteWork implements SiteWork {
 private final JdbcTemplate jdbc;
 TrafficSiteWork(JdbcTemplate jdbc){ this.jdbc=jdbc; }

 @Override public String whyBusy(long siteId){
  Integer held=jdbc.queryForObject("SELECT count(*) FROM traffic.node_reservation WHERE site_id=? AND released_at IS NULL",
   Integer.class,siteId);
  return held!=null&&held>0?"ROADS_STILL_GRANTED":null;
 }
}
