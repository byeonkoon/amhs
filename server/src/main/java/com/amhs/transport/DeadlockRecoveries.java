package com.amhs.transport;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.CommandStatus;
import com.amhs.traffic.DeadlockPlan;
import com.amhs.traffic.DeadlockRecovery;
import com.amhs.traffic.TrafficControl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An operator breaks a deadlock by choosing one vehicle of the chain to give way.
 *
 * <p>Only a vehicle that holds the node the one before it waits for, ahead of where it stands and
 * with nobody standing there, can be chosen: giving back its road then frees that node. When every
 * node in the chain has a vehicle standing on it, nothing the controller can give back helps; a
 * person moves a vehicle by hand, and that is not decided here.
 *
 * <p>The chosen vehicle is told to stop, and its unsent movement commands are taken back. Its road
 * is given back only once it is seen standing still on a node in a report made after the stop
 * settled: a vehicle that may still be driving on what it was given keeps it. From then on it is
 * granted nothing new until the vehicle it gave way to has taken the node (or somebody else has,
 * or nobody waits for it any more); then it carries on as usual. Nothing is released on a timer,
 * nothing is reversed, and the operator can call it off at any time.
 */
@Service
public class DeadlockRecoveries {
 private final TrafficControl traffic; private final FleetState fleet; private final CommandOutbox outbox;
 private final TransportLedger ledger;

 public DeadlockRecoveries(TrafficControl traffic,FleetState fleet,CommandOutbox outbox,TransportLedger ledger){
  this.traffic=traffic; this.fleet=fleet; this.outbox=outbox; this.ledger=ledger;
 }

 /** What one pass did with one recovery. */
 public record Step(long recoveryId,long vehicleId,String decision){}

 @Transactional(readOnly=true)
 public List<DeadlockPlan> plans(long siteId){ return traffic.deadlockPlans(siteId); }

 @Transactional(readOnly=true)
 public List<DeadlockRecovery> list(long siteId){ return traffic.recoveries(siteId,20); }

 /** The operator chooses {@code vehicleId} to give way. A reason is required: it is a decision about the floor. */
 @Transactional
 public DeadlockRecovery start(long siteId,long vehicleId,String reason,String actor){
  if(reason==null||reason.isBlank())
   throw new TransportRejected("REASON_REQUIRED","say why this vehicle gives way (X-Amhs-Reason)",false);
  // One recovery per chain is a decision about the chain, not about the vehicle, so the boundary
  // has to be the chain too. Locking only the chosen vehicle lets two requests naming different
  // vehicles of the same chain each read "no recovery open" and each start one, and both vehicles
  // give way. The decision is serialised per site and the plans are read inside it; the site lock
  // is taken before any vehicle or node lock, so it adds no new order to get wrong.
  traffic.lockRecoveryDecisions(siteId);
  fleet.lockForAssignment(siteId,List.of(vehicleId));
  var plan=traffic.deadlockPlans(siteId).stream().filter(p->p.vehicleIds().contains(vehicleId)).findFirst()
   .orElseThrow(()->new TransportRejected("NOT_IN_DEADLOCK","the vehicle is not part of a deadlock now",true));
  if(plan.recoveryId()!=null)
   throw new TransportRejected("RECOVERY_ALREADY_OPEN","a vehicle of this chain is already giving way",true);
  var link=plan.links().stream().filter(l->l.blockerId()==vehicleId&&l.releasable()).findFirst()
   .orElseThrow(()->new TransportRejected("GIVING_WAY_WOULD_NOT_HELP",
    "the node waited for is stood on, or not held ahead of this vehicle; a vehicle has to be moved by hand",false));
  var vehicle=fleet.find(siteId,ledger.activeRevision(siteId),vehicleId)
   .orElseThrow(()->new TransportRejected("VEHICLE_NOT_FOUND","unknown vehicle",false));
  if(vehicle.nodeId()==null) throw new TransportRejected("POSITION_UNKNOWN","where the vehicle is is not known",true);
  if(!vehicle.online()||!vehicle.remote())
   throw new TransportRejected("VEHICLE_NOT_CONTROLLABLE","the vehicle is offline or not under remote control",true);

  // Movement it has not been sent yet is taken back; what it may already have keeps its road until
  // it stops. An order's movement counts: commands for one vehicle leave in the order they were
  // written, so a movement left behind holds back the stop that was meant to follow it.
  outbox.withdrawMovementForVehicle(siteId,vehicleId,"DEADLOCK_RECOVERY");
  String stopId="deadlock-recovery-"+vehicleId+"-"+UUID.randomUUID().toString().substring(0,8)+"-stop";
  DeadlockRecovery recovery;
  try{
   recovery=traffic.openRecovery(siteId,vehicleId,link.vehicleId(),link.wantedNodeId(),plan.vehicleIds(),stopId,reason,actor);
  }catch(IllegalStateException e){
   throw new TransportRejected("RECOVERY_ALREADY_OPEN","the vehicle is already giving way",true);
  }
  // What a STOP is on the wire is the communication contract's to decide; a simulated vehicle stops
  // at the end of the segment it is on.
  outbox.enqueueForVehicle(siteId,vehicleId,stopId,"STOP",
   "{\"deadlockRecoveryId\":"+recovery.id()+",\"vehicleId\":"+vehicleId+",\"reason\":\"DEADLOCK_RECOVERY\"}");
  return recovery;
 }

 /** The operator calls it off. A stop not yet sent is taken back; the vehicle then asks for road as usual. */
 @Transactional
 public DeadlockRecovery close(long siteId,long recoveryId){
  var recovery=traffic.recovery(siteId,recoveryId)
   .orElseThrow(()->new TransportRejected("RECOVERY_NOT_FOUND","unknown recovery",false));
  if(!recovery.open()) return recovery;
  if(recovery.stopCommandId()!=null) outbox.withdrawIfUnsent(recovery.stopCommandId(),"RECOVERY_CLOSED");
  return traffic.finishRecovery(recoveryId,DeadlockRecovery.ABANDONED,"OPERATOR_CLOSED");
 }

 /**
  * One pass: a stopping vehicle seen standing still gives back its road; a vehicle that has given
  * way stops doing so once it has served its purpose. Runs before movement is resumed, so the
  * vehicle given way to can take the node in the same pass.
  */
 @Transactional
 public List<Step> advance(long siteId){
  var out=new ArrayList<Step>();
  long revisionId=ledger.activeRevision(siteId);
  var waits=traffic.waitingFor(siteId);
  for(var r:traffic.recoveries(siteId,0)){
   if(!r.open()||!DeadlockRecovery.STOPPING.equals(r.status())) continue;
   // The chain broke by itself (or the waiter gave up): nothing left to give way for.
   boolean stillWanted=waits.stream().anyMatch(w->w.vehicleId()==r.beneficiaryVehicleId()&&w.wantedNodeId()==r.wantedNodeId());
   if(!stillWanted){
    // A stop that never left must not reach the vehicle later and cut short its next move.
    if(r.stopCommandId()!=null) outbox.withdrawIfUnsent(r.stopCommandId(),"RECOVERY_DONE");
    traffic.finishRecovery(r.id(),DeadlockRecovery.DONE,"NO_LONGER_WAITED_FOR");
    out.add(new Step(r.id(),r.vehicleId(),"NO_LONGER_WAITED_FOR"));
    continue;
   }
   if(!outbox.unresolvedForVehicle(siteId,r.vehicleId()).isEmpty()){ out.add(new Step(r.id(),r.vehicleId(),"STOP_OUTSTANDING")); continue; }
   if(r.stopSettledAt()==null){
    // Settled now; only a report made after this moment says how the vehicle stands after the stop.
    traffic.recoveryStopSettled(r.id());
    out.add(new Step(r.id(),r.vehicleId(),"WAITING_FOR_STANDSTILL"));
    continue;
   }
   var v=fleet.find(siteId,revisionId,r.vehicleId()).orElse(null);
   boolean still=v!=null&&v.online()&&v.nodeId()!=null&&!v.betweenNodes()&&v.idle()&&"IDLE".equals(v.effectiveStatus())
    &&v.reportedAt()!=null&&v.reportedAt().isAfter(r.stopSettledAt());
   if(!still){ out.add(new Step(r.id(),r.vehicleId(),"WAITING_FOR_STANDSTILL")); continue; }
   traffic.releaseForRecovery(r.id(),v.nodeId());
   out.add(new Step(r.id(),r.vehicleId(),"ROAD_GIVEN_BACK"));
  }
  for(var done:traffic.settleYielding(siteId)) out.add(new Step(done.id(),done.vehicleId(),done.finishReason()));
  return out;
 }
}
