package com.amhs.overview;
import com.amhs.alarm.SiteAlarms;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.inventory.CarrierPlacements;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.transport.OrderStatus;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The site in one answer, for somebody who has just walked up to the screen.
 *
 * <p>Every number here says what it counted, because a figure on an overview is read as a fact
 * about the site and almost never looked up afterwards. Nothing is averaged across things that are
 * not alike, nothing is filled in where there is no answer, and no number is derived from a capped
 * list -- a count taken from "the most recent few" is right only until the site gets busy, which is
 * exactly when somebody looks.
 *
 * <p>It asks the same modules the screens ask. There is no overview table: a second place where the
 * same facts are kept is a second place for them to be wrong.
 */
@Service
public class SiteOverview {
 private final FleetState fleet; private final TransportLedger ledger;
 private final OperationOptionConfiguration operations; private final SiteAlarms alarms;
 private final CarrierPlacements placements;

 public SiteOverview(FleetState fleet,TransportLedger ledger,OperationOptionConfiguration operations,
                     SiteAlarms alarms,CarrierPlacements placements){
  this.fleet=fleet; this.ledger=ledger; this.operations=operations;
  this.alarms=alarms; this.placements=placements;
 }

 /** How the site is being run. */
 public record Operation(String mode,String runMode,boolean autoDispatch){}
 /**
  * The fleet by what each vehicle can be asked to do.
  *
  * <p>{@code usable} is what could take work right now. {@code notReporting} has never said where
  * it is -- which is not the same as having said so a while ago, and the two are counted apart
  * because only one of them is something going wrong.
  */
 public record Fleet(int configured,int usable,int working,int outOfService,int notReporting,int stale){}
 /**
  * Work in hand. {@code needingAPerson} is waiting on somebody rather than on a vehicle, which is
  * the only one of these that will not move on its own.
  */
 public record Work(int queued,int running,int needingAPerson,Long oldestQueuedSeconds){}
 /**
  * What finished in the window. {@code medianSeconds} is the middle time from a job starting to it
  * ending, over the completed ones only -- a median rather than a mean because one job that went
  * wrong should not be able to describe the rest, and {@code measured} says how many it is the
  * middle of, so a median of two is visible as a median of two.
  */
 public record Throughput(int windowMinutes,int finished,int completed,int cancelled,int failed,
                          Long medianSeconds,int measured){}
 public record Overview(Operation operation,Fleet fleet,Work work,Throughput throughput,
                        SiteAlarms.Count alarms,CarrierPlacements.Summary stored,OffsetDateTime at){}

 @Transactional(readOnly=true)
 public Overview overview(long siteId,int windowMinutes){
  int window=Math.max(1,Math.min(windowMinutes,1440));
  var options=operations.get(siteId).settings();
  long revisionId=ledger.activeRevision(siteId);
  var now=OffsetDateTime.now();
  return new Overview(
   new Operation(operations.mode(siteId),options.runMode(),options.autoDispatch()),
   fleetOf(siteId,revisionId,options.positionStaleSeconds(),now),
   workOf(siteId,now),
   throughputOf(siteId,window,now),
   alarms.count(siteId),
   placements.summary(siteId),
   now);
 }

 private Fleet fleetOf(long siteId,long revisionId,int staleSeconds,OffsetDateTime now){
  var working=ledger.assignedVehicleIds(siteId);
  var staleBefore=now.minusSeconds(Math.max(1,staleSeconds));
  int configured=0,usable=0,outOfService=0,notReporting=0,stale=0,busy=0;
  for(VehicleSnapshot v:fleet.snapshot(siteId,revisionId)){
   configured++;
   if(v.outOfService()) outOfService++;
   if(working.contains(v.id())) busy++;
   // Never having reported is a different thing from having reported a while ago: one is a vehicle
   // nobody has heard from at all, the other is one that stopped. Only the second is a fault.
   if(v.reportedAt()==null||"INITIAL_CONFIGURATION".equals(v.source())) notReporting++;
   else if(!v.reportedSince(staleBefore)) stale++;
   if(v.enabled()&&v.assignmentEnabled()&&!v.outOfService()&&v.online()&&v.remote()) usable++;
  }
  return new Fleet(configured,usable,busy,outOfService,notReporting,stale);
 }

 private Work workOf(long siteId,OffsetDateTime now){
  int queued=0,running=0,needingAPerson=0; Long oldest=null;
  for(TransportOrder o:ledger.listActive(siteId,1000)){
   if(o.status()==OrderStatus.RECOVERY_REQUIRED){ needingAPerson++; continue; }
   if(o.status()==OrderStatus.QUEUED){
    queued++;
    long waited=Duration.between(o.createdAt(),now).toSeconds();
    if(oldest==null||waited>oldest) oldest=waited;
   }else running++;
  }
  return new Work(queued,running,needingAPerson,oldest);
 }

 private Throughput throughputOf(long siteId,int windowMinutes,OffsetDateTime now){
  var finished=ledger.finishedSince(siteId,now.minusMinutes(windowMinutes));
  int completed=0,cancelled=0,failed=0;
  var durations=new ArrayList<Long>();
  for(TransportOrder o:finished){
   switch(o.status()){
    case COMPLETED -> {
     completed++;
     // Only a job that both started and ended has a length. One that was cancelled before it
     // started has none, and counting it as zero would quietly pull the middle down.
     if(o.startedAt()!=null&&o.terminalAt()!=null)
      durations.add(Duration.between(o.startedAt(),o.terminalAt()).toSeconds());
    }
    case CANCELLED -> cancelled++;
    case FAILED -> failed++;
    default -> { }
   }
  }
  durations.sort(null);
  Long median=durations.isEmpty()?null:middleOf(durations);
  return new Throughput(windowMinutes,finished.size(),completed,cancelled,failed,median,durations.size());
 }

 /** The middle value; with an even count, the lower of the two middles rather than an invented one. */
 private static long middleOf(List<Long> sorted){ return sorted.get((sorted.size()-1)/2); }
}
