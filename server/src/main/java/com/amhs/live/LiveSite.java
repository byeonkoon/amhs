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
 static final java.util.List<String> SLOW=java.util.List.of("orders","deadlocks","zones","inDoubt","deadlockPlans","recoveries","restartCheck");

 private final Map<String,LongFunction<Object>> reads=new LinkedHashMap<>();
 private final TransactionTemplate tx;

 LiveSite(FleetState fleet,TrafficControl traffic,TransportOrders orders,TransportAssignment assignment,
          TransportLedger ledger,ZoneEvacuation moves,CommandOutbox outbox,PlatformTransactionManager transactions,
          com.amhs.transport.RestartCheck restartCheck){
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
  reads.put("deadlockPlans",traffic::deadlockPlans);
  reads.put("recoveries",site->traffic.recoveries(site,20));
  reads.put("restartCheck",restartCheck::state);
 }

 /**
  * What was read, and what could not be.
  *
  * <p>A part that cannot be read is left out so the screen keeps what it had rather than being
  * told it became empty — but it is also named here. Leaving it out silently makes a failed read
  * look exactly like a read where nothing changed, and the screen then goes on calling data that
  * cannot be refreshed current.
  */
 record Reading(Map<String,Object> parts,java.util.List<String> failed){
  boolean complete(){ return failed.isEmpty(); }
 }

 /** The named parts as they are now, with the ones that could not be read reported as such. */
 Reading read(long siteId,java.util.Collection<String> parts){
  var out=new LinkedHashMap<String,Object>();
  var failed=new java.util.ArrayList<String>();
  try{
   tx.executeWithoutResult(status->{
    for(String part:parts){
     try{ out.put(part,reads.get(part).apply(siteId)); }
     catch(RuntimeException e){
      failed.add(part);
      LiveFeed.log.debug("live read {} failed for site {}: {}",part,siteId,e.getMessage());
     }
    }
   });
  }catch(RuntimeException e){
   // The transaction itself could not run, so nothing was read at all — not "nothing changed".
   for(String part:parts) if(!out.containsKey(part)&&!failed.contains(part)) failed.add(part);
   LiveFeed.log.debug("live read for site {} ended badly: {}",siteId,e.getMessage());
  }
  return new Reading(out,java.util.List.copyOf(failed));
 }
}
