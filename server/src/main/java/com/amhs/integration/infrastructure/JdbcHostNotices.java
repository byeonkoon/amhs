package com.amhs.integration.infrastructure;
import com.amhs.integration.HostNotices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;

/**
 * Writes the notice beside the change it is about.
 *
 * <p>Nothing is sent here. The row is the record that something is owed; who says it, when, and how
 * many times is {@link HostNotifier}'s, and keeping the two apart is what lets the transition
 * commit without waiting on somebody else's web server.
 */
@Component
class JdbcHostNotices implements HostNotices {
 private final JdbcTemplate jdbc; private final ObjectMapper json;
 JdbcHostNotices(JdbcTemplate jdbc,ObjectMapper json){ this.jdbc=jdbc; this.json=json; }

 /**
  * {@inheritDoc}
  *
  * <p>MANDATORY rather than REQUIRED: a notice that opened a transaction of its own would be the
  * exact thing this is meant to prevent. Being told at startup is better than finding out when an
  * order ends and nobody upstairs hears.
  */
 /**
  * The transitions that are a progress report rather than an ending.
  *
  * <p>A vehicle was given to the order, and the carrier is on it. Both are things a system
  * upstairs can act on -- showing a job as started, releasing the next one -- and neither is a
  * thing it needs by default, which is why they are asked for.
  */
 private static final java.util.Set<String> PROGRESS=java.util.Set.of("ASSIGNED","TO_DESTINATION");

 @Override
 @Transactional(propagation=Propagation.MANDATORY)
 public boolean announce(long siteId,long orderId,String orderStatus,String reason){
  // Nothing is written for a progress report the system upstairs has not asked for. Deciding it
  // here rather than when sending matters: a notice written and filtered later would be a row
  // waiting in the queue, and switching the setting on would send a morning's worth of history.
  if(PROGRESS.contains(orderStatus)&&!Boolean.TRUE.equals(jdbc.queryForObject(
   "SELECT EXISTS(SELECT 1 FROM integration.host_endpoint WHERE site_id=? AND enabled AND progress_notices)",
   Boolean.class,siteId))) return false;
  // One notice per order and status. A transition recorded twice, or a retried caller, says the
  // same thing -- and saying it twice is what the key is here to prevent.
  String key="order-"+orderId+"-"+orderStatus;
  var body=json.createObjectNode();
  body.put("event","transport-order");
  body.put("orderId",orderId);
  body.put("siteId",siteId);
  body.put("status",orderStatus);
  // Which transition of this order this is. The sender already holds an order's notices in order,
  // so this is the second lock on the same door: a system upstairs that receives them out of order
  // for any reason of its own can still tell which came last, without trusting arrival time.
  var sequence=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM transport.order_event WHERE order_id=?",
   Integer.class,orderId);
  body.put("sequence",sequence==null?0:sequence);
  if(reason!=null&&!reason.isBlank()) body.put("reason",reason);
  // Which vehicle is carrying it, by the name on its side. A progress notice without it says only
  // that something started; with it, the system upstairs can show who has the carrier -- and the
  // code is the site's own name for the vehicle, not an id of this database's.
  var carrier=jdbc.query("SELECT v.code FROM transport.assignment a"
   +" JOIN fleet.vehicle_profile v ON v.id=a.vehicle_id"
   +" WHERE a.order_id=? ORDER BY a.id DESC LIMIT 1",(r,n)->r.getString(1),orderId)
   .stream().findFirst().orElse(null);
  if(carrier!=null) body.put("vehicleCode",carrier);
  // Said in the words the request came in: port codes, and the caller's own idempotency key, which
  // is how the system upstairs recognises which of its orders this is. Ids here are this database's
  // and mean nothing to anybody else.
  var order=jdbc.query("SELECT o.idempotency_key,sp.code,dp.code,o.carrier_code,o.terminal_at,o.request_source"
   +" FROM transport.order o"
   +" JOIN topology.port sp ON sp.id=o.source_port_id"
   +" JOIN topology.port dp ON dp.id=o.destination_port_id"
   +" WHERE o.id=?",(r,n)->{
    var node=json.createObjectNode();
    node.put("idempotencyKey",r.getString(1));
    node.put("sourcePortCode",r.getString(2));
    node.put("destinationPortCode",r.getString(3));
    node.put("carrierCode",r.getString(4));
    var at=r.getObject(5,java.time.OffsetDateTime.class);
    node.put("endedAt",at==null?null:at.toString());
    node.put("requestSource",r.getString(6));
    return node;
   },orderId).stream().findFirst().orElse(null);
  if(order!=null) body.setAll(order);
  String text;
  try{ text=json.writeValueAsString(body); }
  catch(IOException e){ text="{\"event\":\"transport-order\",\"orderId\":"+orderId+"}"; }

  // ON CONFLICT DO NOTHING rather than a read first: two transitions racing on the same order
  // would both find nothing and both insert, and the unique key is the only thing that settles it.
  return jdbc.update("INSERT INTO integration.host_notice(site_id,order_id,notice_key,order_status,body)"
   +" VALUES(?,?,?,?,?) ON CONFLICT(notice_key) DO NOTHING",siteId,orderId,key,orderStatus,text)>0;
 }
}
