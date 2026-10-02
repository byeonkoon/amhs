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
 public SiteSweep(TransportOrders orders,TransportAssignment assignment,ContinuousMovement movement,
                  DeviceGateway gateway,OperationOptionConfiguration options,ZoneEvacuation evacuation){
  this.orders=orders; this.assignment=assignment; this.movement=movement;
  this.gateway=gateway; this.options=options; this.evacuation=evacuation;
 }

 /** What one pass did. {@code skipped} explains each order the pass could not take on. */
 public record Sweep(boolean autoDispatch,List<Long> assigned,List<Skipped> skipped,
                     List<ContinuousMovement.Extension> resumed,List<ContinuousMovement.Extension> extended,
                     List<ZoneEvacuation.Step> evacuated,DeviceGateway.Publication published){
  public Sweep {
   assigned=List.copyOf(assigned); skipped=List.copyOf(skipped);
   resumed=List.copyOf(resumed); extended=List.copyOf(extended); evacuated=List.copyOf(evacuated);
  }
 }
 public record Skipped(long orderId,String reason){}

 public Sweep run(long siteId,String actorCode,String traceId){
  boolean autoDispatch=options.get(siteId).settings().autoDispatch();
  var assigned=new ArrayList<Long>(); var skipped=new ArrayList<Skipped>();
  if(autoDispatch){
   // Oldest of the most urgent first; a newer order must not take a port or vehicle from an older one.
   for(var order:orders.dispatchQueue(siteId,200)){
    try{
     var result=assignment.assign(siteId,order.id(),actorCode,traceId);
     if(result.created()) assigned.add(order.id());
    }catch(TransportRejected rejection){
     // Waiting for a vehicle or for a road to clear is the ordinary answer here. The pass
     // records it against the order and carries on instead of abandoning the rest.
     skipped.add(new Skipped(order.id(),rejection.code()));
    }
   }
  }
  var resumed=movement.resumeAll(siteId);
  var extended=movement.extendAll(siteId);
  var evacuated=new ArrayList<>(evacuation.evacuateAll(siteId));
  // Moves the controller starts on its own: out of a waiting vehicle's way first, then home.
  evacuated.addAll(evacuation.yieldAll(siteId));
  evacuated.addAll(evacuation.returnHome(siteId));
  var published=gateway.publishPending(siteId);
  return new Sweep(autoDispatch,assigned,skipped,resumed,extended,evacuated,published);
 }
}
