package com.amhs.transport;
import com.amhs.platform.ControlTiming;
import com.amhs.platform.OperationOptionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What keeps a real site going round.
 *
 * <p>The pass is where assignment, resuming, extending, the controller's own moves and the sending
 * happen, and until now the only things that ran it were the simulation loop -- which only looks at
 * simulated sites -- and an operator calling the API. A site in OPERATION therefore dispatched
 * nothing by itself: the queue filled up and waited for somebody to press something. That is the
 * gap this closes, and it was found by looking at which loops a load run had, not by a test.
 *
 * <p>Simulated sites are left to the simulation loop. It does the pass between placing its vehicles
 * and letting them move, in that order and on one thread, and two passes arriving from two
 * directions would be a race nobody asked for. So this one takes the rest.
 *
 * <p>Its own thread, like the notice sender's and the equipment links'. A pass waits on the
 * database and on whatever the device links do; on the shared scheduler it would be delaying the
 * 100ms PLC read, which is the loop everything above trusts for where a vehicle is.
 *
 * <p>{@code amhs.sweep.auto=false} turns it off. Tests run the pass by hand -- a background pass
 * would dispatch work in the middle of a case about what dispatch does.
 */
@Component
@ConditionalOnProperty(name="amhs.sweep.auto",havingValue="true",matchIfMissing=true)
class SiteSweepLoop {
 private static final Logger log=LoggerFactory.getLogger(SiteSweepLoop.class);
 private final SiteSweep sweep; private final OperationOptionConfiguration options;
 private final ControlTiming timing; private final long tickMs;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
  var thread=new Thread(r,"site-sweep");
  thread.setDaemon(true);
  return thread;
 });
 private final AtomicBoolean passing=new AtomicBoolean();

 SiteSweepLoop(SiteSweep sweep,OperationOptionConfiguration options,ControlTiming timing,
               @Value("${amhs.sweep.tick-ms:1000}") long tickMs){
  this.sweep=sweep; this.options=options; this.timing=timing; this.tickMs=tickMs;
 }
 @jakarta.annotation.PreDestroy void stop(){ worker.shutdownNow(); }

 @Scheduled(fixedDelayString="${amhs.sweep.tick-ms:1000}")
 void tick(){
  if(!passing.compareAndSet(false,true)) return;
  try{
   worker.execute(()->{
    try(var pass=timing.begin("site-sweep",tickMs)){ sweepAll(); }
    catch(RuntimeException e){ log.error("site sweep pass failed",e); }
    finally{ passing.set(false); }
   });
  }catch(RuntimeException e){ passing.set(false); throw e; }
 }

 private void sweepAll(){
  var simulated=new HashSet<>(options.simulatedSites());
  for(long siteId:options.sites()){
   if(simulated.contains(siteId)) continue;      // the simulation loop's, with its own ordering
   try{ sweep.run(siteId,"controller",null); }
   catch(RuntimeException e){
    // One site that cannot be swept must not stop the others, and the next pass sees the state as
    // it then is.
    log.warn("sweep skipped site {}: {}",siteId,e.getMessage());
   }
  }
 }
}
