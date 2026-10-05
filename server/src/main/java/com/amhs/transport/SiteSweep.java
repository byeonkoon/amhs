package com.amhs.transport;
import com.amhs.integration.DeviceGateway;
import com.amhs.platform.OperationOptionConfiguration;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * One pass of the site's execution loop: take on waiting work, restart vehicles that have stopped
 * at the end of their authority, lengthen what is already running, then put whatever that produced
 * on the wire.
 *
 * <p>The order matters. Assigning first means a newly assigned order's command is published in the
 * same pass instead of waiting for the next one, and extending before publishing means a MORE and
 * the movement it extends are never sent out of sequence. Resuming comes before extending because a
 * vehicle that was just restarted is not yet moving and has nothing to extend. Moving idle vehicles
 * out of zones comes after assigning, so a vehicle that new work can use leaves with that work
 * instead of being sent somewhere first.
 *
 * <p>Nothing here runs on a timer yet. A caller decides when a pass happens, so a site that is not
 * being watched does not quietly command vehicles.
 */
@Service
public class SiteSweep {
 private final TransportOrders orders; private final TransportAssignment assignment;
 private final ContinuousMovement movement; private final DeviceGateway gateway;
 private final OperationOptionConfiguration options; private final ZoneEvacuation evacuation;
 private final DeadlockRecoveries recoveries;
 public SiteSweep(TransportOrders orders,TransportAssignment assignment,ContinuousMovement movement,
                  DeviceGateway gateway,OperationOptionConfiguration options,ZoneEvacuation evacuation,
                  DeadlockRecoveries recoveries){
  this.orders=orders; this.assignment=assignment; this.movement=movement;
  this.gateway=gateway; this.options=options; this.evacuation=evacuation; this.recoveries=recoveries;
 }

 /**
  * What one pass did. {@code skipped} explains each order the pass could not take on, and
  * {@code running} is false when the site is stopped and the pass therefore did nothing at all.
  */
 public record Sweep(boolean autoDispatch,List<Long> assigned,List<Skipped> skipped,
                     List<ContinuousMovement.Extension> resumed,List<ContinuousMovement.Extension> extended,
                     List<ZoneEvacuation.Step> evacuated,DeviceGateway.Publication published,
                     boolean running){
  public Sweep {
   assigned=List.copyOf(assigned); skipped=List.copyOf(skipped);
   resumed=List.copyOf(resumed); extended=List.copyOf(extended); evacuated=List.copyOf(evacuated);
  }
  public Sweep(boolean autoDispatch,List<Long> assigned,List<Skipped> skipped,
               List<ContinuousMovement.Extension> resumed,List<ContinuousMovement.Extension> extended,
               List<ZoneEvacuation.Step> evacuated,DeviceGateway.Publication published){
   this(autoDispatch,assigned,skipped,resumed,extended,evacuated,published,true);
  }
  /** A pass that did nothing because the site is stopped. */
  static Sweep stopped(){
   return new Sweep(false,List.of(),List.of(),List.of(),List.of(),List.of(),
    DeviceGateway.Publication.nothing(),false);
  }
 }
 public record Skipped(long orderId,String reason){}

 /**
  * One pass, unless the site is stopped.
  *
  * <p>A stopped site progresses nothing: the pass is where assignment, resuming, extending, the
  * controller's own moves and the sending all happen, so the stop is one answer here rather than
  * six checks spread through them. Work may still be accepted while stopped; it waits in the queue,
  * which is what a queue is for.
  */
 public Sweep run(long siteId,String actorCode,String traceId){
  if(!options.sequenceRunning(siteId)) return Sweep.stopped();
  boolean autoDispatch=options.get(siteId).settings().autoDispatch();
  var assigned=new ArrayList<Long>(); var skipped=new ArrayList<Skipped>();
  if(autoDispatch){
   // Oldest of the most urgent first; a newer order must not take a port or vehicle from an older
   // one. The whole queue is walked page by page, so orders nobody can take now do not hide the ones
   // behind them. An order is only tried while a vehicle of its kind is free; the rest are noted.
   var free=new java.util.EnumMap<>(assignment.freeVehicles(siteId));
   // Once a kind has no free vehicle, its first order is still tried so the reason is the real one;
   // the orders of that kind behind it are given the same reason without another attempt.
   var noVehicle=new java.util.EnumMap<com.amhs.platform.VehicleType,String>(com.amhs.platform.VehicleType.class);
   TransportOrder last=null;
   for(var page=orders.dispatchQueue(siteId,null,200);!page.isEmpty();page=orders.dispatchQueue(siteId,last,200)){
    for(var order:page){
     last=order;
     var kind=order.vehicleType();
     if(free.getOrDefault(kind,0)<=0&&noVehicle.containsKey(kind)){ skipped.add(new Skipped(order.id(),noVehicle.get(kind))); continue; }
     try{
      var result=assignment.assign(siteId,order.id(),actorCode,traceId);
      if(result.created()){ assigned.add(order.id()); free.merge(kind,-1,Integer::sum); }
     }catch(TransportRejected rejection){
      // Waiting for a vehicle or for a road to clear is the ordinary answer here. The pass
      // records it against the order and carries on instead of abandoning the rest.
      skipped.add(new Skipped(order.id(),rejection.code()));
      if(free.getOrDefault(kind,0)<=0) noVehicle.putIfAbsent(kind,rejection.code());
     }
    }
    if(page.size()<200) break;
   }
  }
  // A vehicle giving way to break a deadlock hands back its road before anyone resumes, so the one
  // it gave way to can take the node in this same pass.
  recoveries.advance(siteId);
  var resumed=movement.resumeAll(siteId);
  var extended=movement.extendAll(siteId);
  var evacuated=new ArrayList<>(evacuation.evacuateAll(siteId));
  // Moves the controller starts on its own: charging first, then out of a waiting vehicle's way,
  // then home.
  evacuated.addAll(evacuation.chargeAll(siteId,assignment.freeVehicles(siteId)));
  evacuated.addAll(evacuation.yieldAll(siteId));
  evacuated.addAll(evacuation.returnHome(siteId));
  var published=gateway.publishPending(siteId);
  return new Sweep(autoDispatch,assigned,skipped,resumed,extended,evacuated,published);
 }
}
