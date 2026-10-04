package com.amhs.alarm;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.transport.TransportLedger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.TransientDataAccessResourceException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * A fault is remembered only once something was decided about it.
 *
 * <p>The watcher skips a fault it has already acted on, which is what keeps it from arguing with an
 * operator. That skip has to rest on a decision that actually happened: if the write failed, the
 * vehicle is still in service and still faulted, and a watcher that remembered the attempt would
 * leave it being handed work with nobody looking again.
 */
class FaultRetryTest {
 private static final long SITE=1, REVISION=1, OHT=1;

 /** An OHT standing in error and still in service: the state the watcher is supposed to act on. */
 private static VehicleSnapshot inService(){
  return new VehicleSnapshot(OHT,"OHT-01",com.amhs.platform.VehicleType.OHT,101L,
   true,true,true,true,false,false,"SIMULATED",java.time.OffsetDateTime.now(),
   null,null,"REMOTE","ERROR",null,null,null,2000,
   100.0,"DISABLED",null,100.0,java.time.OffsetDateTime.now(),
   null,null,null);
 }
 private static Alarm error(long vehicleId){
  return new Alarm("VEHICLE_ERROR:"+vehicleId,Alarm.Severity.CRITICAL,"차량 에러","OHT-01",
   "차량이 에러 상태를 보고했습니다.","작업을 받지 않습니다.",null,"마지막 보고","fleet");
 }

 @Test void aFailedExclusionIsTriedAgainOnTheNextLook(){
  var alarms=Mockito.mock(SiteAlarms.class);
  var fleet=Mockito.mock(FleetState.class);
  var ledger=Mockito.mock(TransportLedger.class);
  var operations=Mockito.mock(OperationOptionConfiguration.class);
  Mockito.when(alarms.alarms(SITE)).thenReturn(List.of(error(OHT)));
  Mockito.when(ledger.activeRevision(SITE)).thenReturn(REVISION);
  Mockito.when(fleet.find(SITE,REVISION,OHT)).thenReturn(java.util.Optional.of(inService()));
  Mockito.when(fleet.takeOutOfService(Mockito.eq(SITE),Mockito.eq(REVISION),Mockito.eq(OHT),
    Mockito.anyString(),Mockito.eq("system")))
   .thenThrow(new TransientDataAccessResourceException("the database went away for a moment"))
   .thenReturn(inService());
  var response=new FaultResponse(alarms,fleet,ledger,operations);

  assertThat(response.respond(SITE)).as("the write failed, so nothing was taken out").isEmpty();
  assertThat(response.respond(SITE)).as("the same fault, still not dealt with").contains(OHT);

  // Twice, not once: the first attempt must not have been remembered as a decision.
  Mockito.verify(fleet,Mockito.times(2))
   .takeOutOfService(Mockito.eq(SITE),Mockito.eq(REVISION),Mockito.eq(OHT),Mockito.anyString(),Mockito.eq("system"));
 }

 /** A look that cannot read the site decides nothing, and so remembers nothing. */
 @Test void aSiteThatCouldNotBeReadIsNotRememberedAsQuiet(){
  var alarms=Mockito.mock(SiteAlarms.class);
  var fleet=Mockito.mock(FleetState.class);
  var ledger=Mockito.mock(TransportLedger.class);
  var operations=Mockito.mock(OperationOptionConfiguration.class);
  Mockito.when(alarms.alarms(SITE))
   .thenThrow(new TransientDataAccessResourceException("the database went away for a moment"))
   .thenReturn(List.of(error(OHT)));
  Mockito.when(ledger.activeRevision(SITE)).thenReturn(REVISION);
  Mockito.when(fleet.find(SITE,REVISION,OHT)).thenReturn(java.util.Optional.of(inService()));
  Mockito.when(operations.sites()).thenReturn(List.of(SITE));
  var response=new FaultResponse(alarms,fleet,ledger,operations);

  response.look();   // the failing read: survived, and nothing decided
  assertThat(response.respond(SITE)).as("the fault is still news").contains(OHT);
 }
}
