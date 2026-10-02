package com.amhs.live;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.TransportAssignment;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrders;
import com.amhs.transport.ZoneEvacuation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * What a screen follows live at one site, read in one repeatable-read transaction so the parts
 * agree with each other (a hold never names a vehicle the vehicle list has not caught up with).
 * The same reads the HTTP endpoints answer, under the same names the screen already uses.
 */
@Component
class LiveSite {
 /** Read on every pass: what moves. */
 static final java.util.List<String> FAST=java.util.List.of("revisionId","vehicles","holds","headings","moves","assignments");
 /** Read every few passes: what changes with work rather than with motion. */
 static final java.util.List<String> SLOW=java.util.List.of("orders","deadlocks","zones","inDoubt");

 private final Map<String,LongFunction<Object>> reads=new LinkedHashMap<>();
 private final TransactionTemplate tx;

 LiveSite(FleetState fleet,TrafficControl traffic,TransportOrders orders,TransportAssignment assignment,
          TransportLedger ledger,ZoneEvacuation moves,CommandOutbox outbox,PlatformTransactionManager transactions){
  tx=new TransactionTemplate(transactions);
  tx.setReadOnly(true);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  reads.put("revisionId",ledger::activeRevision);
  reads.put("vehicles",site->fleet.snapshot(site,ledger.activeRevision(site)));
  reads.put("holds",traffic::held);
  reads.put("headings",moves::headings);
  reads.put("moves",moves::openMoves);
  reads.put("assignments",assignment::active);
  reads.put("orders",site->orders.listActive(site,200));
  reads.put("deadlocks",traffic::deadlocks);
  reads.put("zones",traffic::zones);
  reads.put("inDoubt",site->outbox.inDoubt(site,50));
 }

 /**
  * The named parts as they are now. A part that cannot be read is left out, so the screen keeps
  * what it had for that part rather than being told it became empty.
  */
 Map<String,Object> read(long siteId,java.util.Collection<String> parts){
  var out=new LinkedHashMap<String,Object>();
  try{
   tx.executeWithoutResult(status->{
    for(String part:parts){
     try{ out.put(part,reads.get(part).apply(siteId)); }
     catch(RuntimeException e){ LiveFeed.log.debug("live read {} failed for site {}: {}",part,siteId,e.getMessage()); }
    }
   });
  }catch(RuntimeException e){
   // Nothing was written, so a failed commit changes nothing about what was read.
   LiveFeed.log.debug("live read for site {} ended badly: {}",siteId,e.getMessage());
  }
  return out;
 }
}
