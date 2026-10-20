package com.amhs.platform;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How long the loops that drive the plant are taking, and how late they are running.
 *
 * <p>Every loop here has a period it is meant to keep -- the simulation sweep every 1.5s, the
 * motion step every 100ms, the equipment read every 200ms -- and under load the thing that goes
 * wrong first is not a wrong answer but a late one. A sweep that takes longer than its period
 * dispatches less often; a motion step that arrives late moves vehicles in jerks; and loops that
 * share a thread make each other late without any of them being slow.
 *
 * <p>So two numbers per loop, and they are different questions. <b>How long a pass takes</b> is
 * that loop's own work. <b>The gap between passes</b> is what actually happened to it, including
 * everything it waited behind. A loop whose passes are quick and whose gaps are long is a loop
 * being held up by something else, which is the only way to see that from the inside.
 *
 * <p>Nothing is averaged over the life of the server: the counts are since the last reset, so a
 * load run can be measured from a known start rather than against yesterday's idle hours.
 */
@Component
public class ControlTiming {
 private final Map<String,Counter> loops=new ConcurrentHashMap<>();

 /**
  * One loop as it has been running.
  *
  * @param targetMs the period it is meant to keep, for comparison. Null when it has none.
  * @param maxGapMs the longest it ever went between the starts of two passes, which is how long
  *        the plant went without that loop
  */
 public record Loop(String name,long passes,Long targetMs,Long lastMs,Long maxMs,Double meanMs,
                    Long lastGapMs,Long maxGapMs,Double meanGapMs,OffsetDateTime since,
                    OffsetDateTime measuredAt){}

 private static final class Counter {
  final long targetMs;
  final OffsetDateTime since=OffsetDateTime.now();
  final AtomicLong passes=new AtomicLong(), totalMs=new AtomicLong(), maxMs=new AtomicLong();
  final AtomicLong lastMs=new AtomicLong(-1), totalGapMs=new AtomicLong(), maxGapMs=new AtomicLong();
  final AtomicLong lastGapMs=new AtomicLong(-1), gaps=new AtomicLong();
  volatile long startedAtNanos=-1;
  volatile OffsetDateTime measuredAt;
  Counter(long targetMs){ this.targetMs=targetMs; }
 }

 /** One pass, measured. Closed when the pass ends, which is what records it. */
 public final class Pass implements AutoCloseable {
  private final Counter counter; private final long startNanos;
  private Pass(Counter counter,long startNanos){ this.counter=counter; this.startNanos=startNanos; }
  @Override public void close(){
   long millis=(System.nanoTime()-startNanos)/1_000_000;
   counter.passes.incrementAndGet();
   counter.totalMs.addAndGet(millis);
   counter.lastMs.set(millis);
   counter.maxMs.accumulateAndGet(millis,Math::max);
   counter.measuredAt=OffsetDateTime.now();
  }
 }

 /**
  * Starts measuring a pass of that loop.
  *
  * <p>The gap is taken here rather than at the end, because what matters is how long the plant
  * went without this loop running -- which is from one start to the next, whatever happened in
  * between.
  */
 public Pass begin(String name,long targetMs){
  var counter=loops.computeIfAbsent(name,n->new Counter(targetMs));
  long now=System.nanoTime();
  long previous=counter.startedAtNanos;
  if(previous>0){
   long gap=(now-previous)/1_000_000;
   counter.gaps.incrementAndGet();
   counter.totalGapMs.addAndGet(gap);
   counter.lastGapMs.set(gap);
   counter.maxGapMs.accumulateAndGet(gap,Math::max);
  }
  counter.startedAtNanos=now;
  return new Pass(counter,now);
 }

 /** Every loop that has run, by name. */
 public List<Loop> loops(){
  var out=new ArrayList<Loop>();
  loops.forEach((name,c)->{
   long passes=c.passes.get(), gaps=c.gaps.get();
   out.add(new Loop(name,passes,c.targetMs<=0?null:c.targetMs,
    c.lastMs.get()<0?null:c.lastMs.get(),passes==0?null:c.maxMs.get(),
    passes==0?null:(double)c.totalMs.get()/passes,
    c.lastGapMs.get()<0?null:c.lastGapMs.get(),gaps==0?null:c.maxGapMs.get(),
    gaps==0?null:(double)c.totalGapMs.get()/gaps,c.since,c.measuredAt));
  });
  out.sort((a,b)->a.name().compareTo(b.name()));
  return out;
 }

 /** Starts the counting again, which is how a run is measured from its own beginning. */
 public void reset(){ loops.clear(); }
}
