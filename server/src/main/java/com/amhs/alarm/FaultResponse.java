package com.amhs.alarm;
import com.amhs.fleet.FleetState;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.transport.TransportLedger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes a vehicle out of service when it reports something that means it should not be worked.
 *
 * <p>Only two conditions do this, and both are ones the alarm already says leave the vehicle doing
 * nothing useful: it has reported an error, or it is not picking up power from the rail. Acting on
 * them makes the system behave the way the alarm already describes, rather than leaving a vehicle
 * that cannot work being handed work until somebody notices.
 *
 * <p>Deliberately not included: a vehicle that has gone offline or whose reports are late. Comms
 * come and go, the vehicle itself may be perfectly fine, and taking it out on every blip would
 * churn. Nor a low charge, which the charging regime already handles by sending it to charge --
 * that is a vehicle doing its job, not one that cannot.
 *
 * <p><b>Putting it back is never automatic.</b> A machine deciding a vehicle must stop is
 * conservative; a machine deciding it may start again is not. A vehicle that errored and recovered
 * may still want looking at, and the only thing that knows is a person.
 */
@Component
class FaultResponse {
 private final SiteAlarms alarms; private final FleetState fleet;
 private final TransportLedger ledger; private final OperationOptionConfiguration operations;

 /**
  * Vehicles already faulted at the last look, per site.
  *
  * <p>The trigger is the fault arriving, not the fault being there. Acting on its presence would
  * fight the operator: somebody who puts a still-faulted vehicle back in service on purpose would
  * have it taken out again a second later, forever. Held in memory because it describes this run
  * of the program; after a restart a fault that is present is treated as newly seen, which is the
  * right answer for a controller that has just started and does not know what was decided before.
  */
 private final Map<Long,Set<Long>> faultedLastLook=new ConcurrentHashMap<>();

 FaultResponse(SiteAlarms alarms,FleetState fleet,TransportLedger ledger,
               OperationOptionConfiguration operations){
  this.alarms=alarms; this.fleet=fleet; this.ledger=ledger; this.operations=operations;
 }

 @Scheduled(fixedDelayString="${amhs.alarm.fault-response-ms:3000}")
 void look(){
  for(Long siteId:operations.sites()){
   try{ respond(siteId); }
   catch(RuntimeException e){
    // One site that cannot be read must not stop the others being looked after.
    SiteAlarms.log.debug("fault response skipped site {}: {}",siteId,e.getMessage());
   }
  }
 }

 /** One look at one site. Returns the vehicles it took out of service this time. */
 Set<Long> respond(long siteId){
  var faultedNow=new LinkedHashSet<Long>();
  var reasons=new java.util.LinkedHashMap<Long,String>();
  for(Alarm alarm:alarms.alarms(siteId)){
   String reason=reasonFor(alarm);
   if(reason==null) continue;
   Long vehicleId=vehicleOf(alarm.key());
   if(vehicleId==null) continue;
   faultedNow.add(vehicleId);
   reasons.putIfAbsent(vehicleId,reason);
  }
  var before=faultedLastLook.getOrDefault(siteId,Set.of());
  faultedLastLook.put(siteId,faultedNow);

  long revisionId=ledger.activeRevision(siteId);
  var removed=new LinkedHashSet<Long>();
  for(Long vehicleId:faultedNow){
   if(before.contains(vehicleId)) continue;   // the same fault, still there: already decided
   var vehicle=fleet.find(siteId,revisionId,vehicleId).orElse(null);
   if(vehicle==null||vehicle.outOfService()) continue;
   fleet.takeOutOfService(siteId,revisionId,vehicleId,reasons.get(vehicleId),"system");
   removed.add(vehicleId);
  }
  return removed;
 }

 /** Why this alarm means the vehicle should not be worked, or null when it does not mean that. */
 private static String reasonFor(Alarm alarm){
  if(alarm.key().startsWith("VEHICLE_ERROR:"))
   return "차량이 에러를 보고해 자동으로 작업에서 제외했습니다. 복귀는 사람이 확인한 뒤 직접 합니다.";
  if(alarm.key().startsWith("POWER_PICKUP:"))
   return "레일에서 전력을 받지 못해 자동으로 작업에서 제외했습니다. 정비 구역으로 빼내고, 복귀는 사람이 직접 합니다.";
  return null;
 }
 /** The vehicle an alarm key names, for the rules whose subject is one vehicle. */
 private static Long vehicleOf(String key){
  int colon=key.indexOf(':');
  if(colon<0) return null;
  try{ return Long.valueOf(key.substring(colon+1)); }
  catch(NumberFormatException e){ return null; }
 }
}
