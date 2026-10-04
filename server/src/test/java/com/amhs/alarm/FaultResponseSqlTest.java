package com.amhs.alarm;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * A vehicle that has told the controller it cannot work stops being given work.
 *
 * <p>Three things have to hold. It acts when the fault arrives, so nobody has to notice first. It
 * does not act on the fault merely being there, or it would fight an operator who put the vehicle
 * back on purpose. And it never puts a vehicle back by itself: deciding a vehicle must stop is
 * conservative, deciding it may start again is not.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class FaultResponseSqlTest {
 @Autowired FaultResponse response; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT=1, NODE=101;
 /** Whatever these looks took out of service, including vehicles this test is not about. */
 private final java.util.Set<Long> tookOut=new java.util.LinkedHashSet<>();

 @BeforeEach @AfterEach void leaveItAsItWas(){
  // A vehicle taken out of use on purpose raises no alarms at all, so the one under test is in
  // use, reporting, in service, and with a buffer that has just risen: no fault of any kind.
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT);
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,100));
  // A look is about the whole site, so it may act on a vehicle another test left faulted. Undo
  // exactly what these looks did rather than leaving a vehicle nobody asked about out of service.
  tookOut.add(OHT);
  for(Long vehicleId:tookOut) tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,vehicleId));
  tookOut.clear();
  // The watcher remembers what was faulted last time it looked; start from a clean look.
  look();
 }
 /** One look, remembering what it took out so the test can put it back. */
 private java.util.Set<Long> look(){
  var removed=response.respond(SITE);
  tookOut.addAll(removed);
  return removed;
 }
 private void reports(String status){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,"IDLE".equals(status),false,"SIMULATED",null,"REMOTE",status)));
 }
 private boolean outOfService(){ return fleet.find(SITE,REVISION,OHT).orElseThrow().outOfService(); }

 @Test void aVehicleThatReportsAnErrorIsTakenOutOfWork(){
  reports("IDLE");
  look();
  assertThat(outOfService()).isFalse();

  reports("ERROR");
  assertThat(look()).contains(OHT);

  var vehicle=fleet.find(SITE,REVISION,OHT).orElseThrow();
  assertThat(vehicle.outOfService()).isTrue();
  assertThat(vehicle.outOfServiceBy()).as("the controller did it, and says so").isEqualTo("system");
  assertThat(vehicle.outOfServiceReason()).contains("에러").contains("복귀는 사람이");
 }

 /**
  * The trigger is the fault arriving, not the fault being there.
  *
  * <p>An operator who puts a still-faulted vehicle back has decided something; taking it out again
  * a second later would be the controller arguing with them, forever.
  */
 @Test void itDoesNotTakeBackAVehicleSomebodyPutBackOnPurpose(){
  reports("ERROR");
  assertThat(look()).contains(OHT);
  assertThat(outOfService()).isTrue();

  // Somebody decides it may run after all, while the vehicle is still saying error.
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));
  assertThat(outOfService()).isFalse();

  assertThat(look()).as("the same fault, already decided").doesNotContain(OHT);
  assertThat(look()).doesNotContain(OHT);
  assertThat(outOfService()).as("their decision stands").isFalse();
 }

 /** Clearing the fault does not put the vehicle back. Only a person does that. */
 @Test void recoveringDoesNotPutTheVehicleBackByItself(){
  reports("ERROR");
  look();
  assertThat(outOfService()).isTrue();

  reports("IDLE");
  look();
  assertThat(outOfService())
   .as("it stopped erroring; whether it may run again is not the controller's to decide").isTrue();
 }

 /** A fault that goes and comes back is a new fault, and is acted on again. */
 @Test void aFaultThatReturnsIsActedOnAgain(){
  reports("ERROR");
  assertThat(look()).contains(OHT);
  reports("IDLE");
  look();
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));

  reports("ERROR");
  assertThat(look()).as("a different episode").contains(OHT);
  assertThat(outOfService()).isTrue();
 }

 /**
  * Comms coming and going is not a reason to take a vehicle out.
  *
  * <p>The vehicle may be perfectly fine, nothing can be commanded either way, and acting on every
  * blip would churn.
  */
 @Test void aVehicleThatHasMerelyGoneQuietIsLeftAlone(){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,false,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  assertThat(look()).doesNotContain(OHT);
  assertThat(outOfService()).isFalse();
 }

 /**
  * The other condition: a rail-fed vehicle that is not picking its power up.
  *
  * <p>A buffer that only falls means the vehicle is not being fed, and it will stop where it
  * stands. On a single loop that is in front of everything else, so it is taken out before it
  * stops rather than after.
  */
 @Test void aVehicleNotPickingUpItsPowerIsTakenOutOfWork(){
  // The fall as the trend rule reads it: a peak long enough ago, and far enough above now.
  jdbc.update("UPDATE fleet.vehicle_state SET battery_pct=90,soc_peak_pct=100,"
   +"soc_peak_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=? AND revision_id=?",
   SITE,OHT,REVISION);
  assertThat(fleet.find(SITE,REVISION,OHT).orElseThrow().carriesItsOwnCharge())
   .as("only a vehicle fed from the rail has a buffer rather than a charge").isFalse();

  assertThat(look()).contains(OHT);
  assertThat(fleet.find(SITE,REVISION,OHT).orElseThrow().outOfServiceReason())
   .contains("전력").contains("정비 구역");
 }

 /**
  * A second kind of fault on the same vehicle is news, even while the first one is still there.
  *
  * <p>What has been decided is this fault, not this vehicle. An operator who put a still-erroring
  * vehicle back has said something about the error; they have said nothing about the power pickup
  * it loses afterwards, and that is a different reason to stop working it.
  */
 @Test void aDifferentFaultOnTheSameVehicleIsActedOnAgain(){
  reports("ERROR");
  assertThat(look()).contains(OHT);
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));
  assertThat(look()).as("the error is already decided").doesNotContain(OHT);

  // Still in error, and now not being fed from the rail either.
  jdbc.update("UPDATE fleet.vehicle_state SET battery_pct=90,soc_peak_pct=100,"
   +"soc_peak_at=now()-interval '1 hour' WHERE site_id=? AND vehicle_id=? AND revision_id=?",
   SITE,OHT,REVISION);

  assertThat(look()).as("a fault nobody has decided anything about").contains(OHT);
  assertThat(fleet.find(SITE,REVISION,OHT).orElseThrow().outOfServiceReason()).contains("전력");
 }

 /**
  * A low charge is a vehicle doing its job, not one that cannot.
  *
  * <p>The charging regime already answers it by sending the vehicle to charge. Taking it out of
  * service as well would stop the thing that fixes it.
  */
 @Test void aVehicleLowOnChargeIsStillAVehicleThatWorks(){
  long agv=2; Double was=fleet.find(SITE,REVISION,agv).orElseThrow().batteryPct();
  String policy=jdbc.queryForObject("SELECT charging_policy FROM fleet.vehicle_profile"
   +" WHERE site_id=? AND revision_id=? AND id=?",String.class,SITE,REVISION,agv);
  try{
   // A charge is only low for a vehicle that has one to put back, so this one charges.
   jdbc.update("UPDATE fleet.vehicle_profile SET charging_policy='NEAREST'"
    +" WHERE site_id=? AND revision_id=? AND id=?",SITE,REVISION,agv);
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,agv,1));
   assertThat(fleet.find(SITE,REVISION,agv).orElseThrow().carriesItsOwnCharge()).isTrue();
   assertThat(look()).as("it needs charging, which is not the same as needing a person")
    .doesNotContain(agv);
   assertThat(fleet.find(SITE,REVISION,agv).orElseThrow().outOfService()).isFalse();
  }finally{
   if(was!=null) tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,agv,was));
   jdbc.update("UPDATE fleet.vehicle_profile SET charging_policy=?"
    +" WHERE site_id=? AND revision_id=? AND id=?",policy,SITE,REVISION,agv);
  }
 }
}
