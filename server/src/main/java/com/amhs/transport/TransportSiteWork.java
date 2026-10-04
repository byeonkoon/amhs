package com.amhs.transport;
import com.amhs.platform.SiteWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/**
 * Transport's answer to "may this site's map change now": not while an order or a move is open.
 *
 * <p>Both are written against a revision -- an order's route, a move's nodes -- so a map swapped
 * under them would leave work pointing at track that is not there any more. Finished records are no
 * objection: they name the revision they were carried out on and that revision is kept.
 */
@Component
class TransportSiteWork implements SiteWork {
 private final JdbcTemplate jdbc;
 TransportSiteWork(JdbcTemplate jdbc){ this.jdbc=jdbc; }

 @Override public String whyBusy(long siteId){
  Integer orders=jdbc.queryForObject("SELECT count(*) FROM transport.order"
   +" WHERE site_id=? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",Integer.class,siteId);
  if(orders!=null&&orders>0) return "ORDERS_STILL_OPEN";
  Integer moves=jdbc.queryForObject("SELECT count(*) FROM transport.relocation"
   +" WHERE site_id=? AND finished_at IS NULL",Integer.class,siteId);
  if(moves!=null&&moves>0) return "MOVES_STILL_OPEN";
  return null;
 }
}
