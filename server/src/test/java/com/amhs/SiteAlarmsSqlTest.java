package com.amhs;
import com.amhs.alarm.Alarm;
import com.amhs.alarm.SiteAlarms;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * An alarm is a condition, not an event.
 *
 * <p>Everything worth checking follows from that. It appears as soon as the condition is true
 * without anybody having been watching, it goes when the condition goes rather than when somebody
 * clears it, and it never outlives the thing it is about. A stored list of events gets both of
 * those wrong in opposite directions, which is why there is no stored list.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class SiteAlarmsSqlTest {
 @Autowired SiteAlarms alarms; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT=1, NODE=101;

 @AfterEach void forgetWhatWasAcknowledged(){
  jdbc.update("DELETE FROM platform.alarm_acknowledgement WHERE site_id=?",SITE);
 }
 @AfterEach void leaveItAsItWas(){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT);
 }
 private void reports(String status){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,"IDLE".equals(status),false,"SIMULATED",null,"REMOTE",status)));
 }
 private List<Alarm> about(String keyPrefix){
  return alarms.alarms(SITE).stream().filter(a->a.key().startsWith(keyPrefix)).toList();
 }
 /** Every vehicle rule, for one vehicle: the site has others, and they have their own troubles. */
 private List<Alarm> aboutThisVehicle(){
  return alarms.alarms(SITE).stream()
   .filter(a->a.key().startsWith("VEHICLE_")&&a.key().endsWith(":"+OHT)).toList();
 }

 @Test void aConditionThatBecomesTrueAppearsAndGoesWhenItStops(){
  reports("IDLE");
  assertThat(about("VEHICLE_ERROR:"+OHT)).as("nothing wrong yet").isEmpty();

  // Nobody is watching, nothing is recorded; the vehicle simply says it is in error.
  reports("ERROR");
  assertThat(about("VEHICLE_ERROR:"+OHT)).singleElement().satisfies(a->{
   assertThat(a.severity()).isEqualTo(Alarm.Severity.CRITICAL);
   assertThat(a.subject()).isEqualTo("OHT-01");
   // What it is already costing, which is the part somebody decides on.
   assertThat(a.effect()).isNotBlank().contains("점유");
   assertThat(a.since()).as("a real timestamp from the report itself").isNotNull();
   assertThat(a.sinceMeans()).isEqualTo("마지막 보고");
   assertThat(a.screen()).isEqualTo("fleet");
  });

  // Nobody clears it. The vehicle stops being in error, and that is the whole of it.
  reports("IDLE");
  assertThat(about("VEHICLE_ERROR:"+OHT)).as("fixed is cleared; there is nothing to dismiss").isEmpty();
 }

 /** A vehicle taken out of use on purpose is not a fault, and must not fill the screen as one. */
 @Test void aVehicleSomebodyTookOutOfUseIsNotAnAlarm(){
  reports("ERROR");
  assertThat(about("VEHICLE_ERROR:"+OHT)).hasSize(1);
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  assertThat(about("VEHICLE_ERROR:"+OHT)).isEmpty();
 }

 /** One condition, one line: a vehicle in error is not also reported as merely offline or late. */
 @Test void oneVehicleInTroubleProducesOneLineNotThree(){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,false,true,false,false,"SIMULATED",null,"MANUAL","ERROR")));
  assertThat(aboutThisVehicle()).as("the worst true thing, said once").hasSize(1);
  assertThat(aboutThisVehicle().getFirst().key()).startsWith("VEHICLE_ERROR");
 }

 /**
  * A vehicle that does not charge has no charge to be low on.
  *
  * <p>The threshold belongs to a charging regime, and a vehicle with charging switched off is not
  * in one. Alarming anyway would put a line on the screen that nobody can act on, which is exactly
  * what teaches people to stop reading it.
  */
 @Test void aVehicleThatDoesNotChargeIsNotAlarmedForBeingLow(){
  // A vehicle the site already configured to charge, rather than one forced into a regime it has
  // no charge node for -- the schema refuses that, and rightly.
  // An AGV: the schema itself forbids an OHT from charging at all (ck_oht_charge), which is the
  // same rule this guard follows. The test makes its own conditions rather than hoping the seed
  // holds them -- a reported position, so there is a row to carry a charge, and a threshold.
  var agv=jdbc.queryForObject(
   "SELECT id,charge_node_id,charging_policy FROM fleet.vehicle_profile"
   +" WHERE site_id=? AND v_type='AGV' AND charging_policy<>'DISABLED' AND enabled=TRUE"
   +" ORDER BY id LIMIT 1",
   (r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3)},SITE);
  long vehicleId=(Long)agv[0]; long chargeNode=(Long)agv[1]; String policy=(String)agv[2];
  Integer threshold=jdbc.queryForObject(
   "SELECT charge_critical_pct FROM platform.site_operation WHERE site_id=?",Integer.class,SITE);
  try{
   jdbc.update("UPDATE platform.site_operation SET charge_critical_pct=20 WHERE site_id=?",SITE);
   tx.executeWithoutResult(s->fleet.report(SITE,REVISION,vehicleId,
    new VehicleReport(chargeNode,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,vehicleId,1.0));
   assertThat(about("BATTERY_CRITICAL:"+vehicleId)).as("low, and it charges").hasSize(1);

   jdbc.update("UPDATE fleet.vehicle_profile SET charging_policy='DISABLED',charge_node_id=NULL"
    +" WHERE site_id=? AND id=?",SITE,vehicleId);
   assertThat(about("BATTERY_CRITICAL:"+vehicleId))
    .as("the same charge, no regime it belongs to").isEmpty();
  } finally {
   jdbc.update("UPDATE fleet.vehicle_profile SET charge_node_id=?,charging_policy=? WHERE site_id=? AND id=?",
    chargeNode,policy,SITE,vehicleId);
   jdbc.update("UPDATE platform.site_operation SET charge_critical_pct=? WHERE site_id=?",threshold,SITE);
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,vehicleId,100.0));
  }
 }

 /**
  * A vehicle fed from the rail is watched for direction, not level.
  *
  * <p>Its store is a buffer the rail keeps full, so a level says nothing until far too late and
  * wherever the vehicle happens to be. Only falling -- for long enough, by far enough, without once
  * rising -- is the vehicle not picking its power up. A rise is the event that says it is being fed
  * again, so a rise ends the condition however low the reading is.
  */
 @Test void aRailPoweredVehicleIsWatchedForFallingNotForBeingLow(){
  Integer window=jdbc.queryForObject(
   "SELECT power_drop_window_seconds FROM platform.site_operation WHERE site_id=?",Integer.class,SITE);
  Integer minPct=jdbc.queryForObject(
   "SELECT power_drop_min_pct FROM platform.site_operation WHERE site_id=?",Integer.class,SITE);
  try{
   jdbc.update("UPDATE platform.site_operation SET power_drop_window_seconds=60,power_drop_min_pct=5 WHERE site_id=?",SITE);
   reports("IDLE");

   // Full, then falling: not yet, because it has not been falling for long enough.
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,100.0));
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,88.0));
   assertThat(about("POWER_PICKUP:"+OHT)).as("falling, but not for long enough yet").isEmpty();

   // The same drop, old enough to mean something.
   jdbc.update("UPDATE fleet.vehicle_state SET soc_peak_at=now()-interval '10 minutes'"
    +" WHERE vehicle_id=? AND revision_id=?",OHT,REVISION);
   assertThat(about("POWER_PICKUP:"+OHT)).singleElement().satisfies(a->{
    assertThat(a.severity()).isEqualTo(Alarm.Severity.CRITICAL);
    assertThat(a.subject()).isEqualTo("OHT-01");
    assertThat(a.effect()).contains("정비 구역");
    assertThat(a.sinceMeans()).isEqualTo("마지막으로 오른 시각");
   });
   // And it is not the level that did it: the threshold rule never fires for this vehicle.
   assertThat(about("BATTERY_CRITICAL:"+OHT)).isEmpty();

   // One rise says the rail is feeding it again, and that ends it well below where it started --
   // a buffer that recovers to its working level need never climb back to where it last was full.
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,91.0));
   assertThat(about("POWER_PICKUP:"+OHT)).as("it rose, so it is being fed").isEmpty();
  } finally {
   jdbc.update("UPDATE platform.site_operation SET power_drop_window_seconds=?,power_drop_min_pct=? WHERE site_id=?",
    window,minPct,SITE);
   tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,100.0));
  }
 }

 /**
  * Standing still is not falling, so a charge that is steady keeps starting the clock over.
  *
  * <p>This is the case that matters for a buffer the rail holds full: it reports the same value all
  * day. If only a rise moved the anchor, it would sit frozen at whenever the vehicle first reached
  * full, and a single dip hours later would read as hours of falling -- the window that exists to
  * ride out transients would never be applied at all.
  */
 @Test void aChargeThatIsSteadyKeepsStartingTheClockOver(){
  reports("IDLE");
  // Set rather than arrived at: what is under test is what one reading does to the next.
  jdbc.update("UPDATE fleet.vehicle_state SET battery_pct=100,soc_peak_pct=100,"
   +"soc_peak_at=now()-interval '2 hours' WHERE vehicle_id=? AND revision_id=?",OHT,REVISION);

  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,100.0));
  assertThat(peakAt()).as("flat is not falling, so the fall has not been going on for two hours")
   .isAfter(java.time.OffsetDateTime.now().minusMinutes(1));

  // A hair lower is inside the noise floor: still not a fall.
  var afterFlat=peakAt();
  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,99.95));
  assertThat(peakAt()).as("a hair lower is the same reading").isAfter(afterFlat.minusSeconds(1));

  // Clearly lower is a fall, and it is measured from where it started.
  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,94.0));
  var held=peakAt();
  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,91.0));
  assertThat(peakAt()).as("still falling, so the start of the fall does not move").isEqualTo(held);

  // Rising ends it, from wherever it got to.
  tx.executeWithoutResult(s->fleet.reportBattery(SITE,REVISION,OHT,93.0));
  assertThat(jdbc.queryForObject("SELECT soc_peak_pct FROM fleet.vehicle_state WHERE vehicle_id=? AND revision_id=?",
   Double.class,OHT,REVISION)).isEqualTo(93.0);
 }
 private java.time.OffsetDateTime peakAt(){
  return jdbc.queryForObject("SELECT soc_peak_at FROM fleet.vehicle_state WHERE vehicle_id=? AND revision_id=?",
   java.time.OffsetDateTime.class,OHT,REVISION);
 }

 /** Every line has to say what it is already causing, or the screen teaches people to skip it. */
 @Test void everyAlarmSaysWhatItIsCausingAndWhereToGo(){
  reports("ERROR");
  assertThat(alarms.alarms(SITE)).isNotEmpty().allSatisfy(a->{
   assertThat(a.key()).isNotBlank();
   assertThat(a.rule()).isNotBlank();
   assertThat(a.subject()).isNotBlank();
   assertThat(a.summary()).isNotBlank();
   assertThat(a.effect()).isNotBlank();
   assertThat(a.sinceMeans()).isNotBlank();
   assertThat(a.screen()).isIn("fleet","transport","traffic","diagnostics","inventory");
  });
 }

 @Test void theWorstComesFirstAndTheCountAgreesWithTheList(){
  reports("ERROR");
  var all=alarms.alarms(SITE);
  var severities=all.stream().map(Alarm::severity).toList();
  assertThat(severities).isSortedAccordingTo(java.util.Comparator.naturalOrder());
  assertThat(severities.getFirst()).isEqualTo(Alarm.Severity.CRITICAL);

  var count=alarms.count(SITE);
  assertThat(count.total()).isEqualTo(all.size());
  assertThat(count.critical()).isEqualTo((int)severities.stream().filter(s->s==Alarm.Severity.CRITICAL).count());
  assertThat(count.critical()+count.warning()).isEqualTo(count.total());
 }

 /**
  * Acknowledging is not fixing, and must never look like it.
  *
  * <p>The line stays, because the condition does. What changes is only that the next reader can
  * tell a new fault from one somebody is already standing in front of.
  */
 @Test void sayingYouHaveSeenItDoesNotTakeItOffTheList(){
  reports("ERROR");
  String key="VEHICLE_ERROR:"+OHT;
  assertThat(about(key)).singleElement().satisfies(a->assertThat(a.acknowledged()).isFalse());

  var seen=alarms.acknowledge(SITE,key,"현장 확인 중, 정비 요청함","mgr1");
  assertThat(seen.acknowledged()).isTrue();
  assertThat(seen.seen().reason()).isEqualTo("현장 확인 중, 정비 요청함");
  assertThat(seen.seen().actorCode()).isEqualTo("mgr1");

  assertThat(about(key)).as("still true, so still listed").singleElement()
   .satisfies(a->assertThat(a.acknowledged()).isTrue());
  var count=alarms.count(SITE);
  assertThat(count.unseen()).as("it no longer counts as something nobody has looked at")
   .isLessThan(count.total());
 }

 /** Saying how you know is required: that somebody clicked tells the next reader nothing. */
 @Test void anAcknowledgementHasToSaySomething(){
  reports("ERROR");
  String key="VEHICLE_ERROR:"+OHT;
  assertThatThrownBy(()->alarms.acknowledge(SITE,key,"  ","mgr1"))
   .isInstanceOf(IllegalArgumentException.class).hasMessage("ACKNOWLEDGE_REASON_REQUIRED");
  assertThatThrownBy(()->alarms.acknowledge(SITE,"VEHICLE_ERROR:999999","현장 확인","mgr1"))
   .as("nothing to acknowledge, so nothing is left waiting for the next time")
   .isInstanceOf(IllegalArgumentException.class).hasMessage("ALARM_NOT_LIVE");
 }

 /**
  * An acknowledgement belongs to one occurrence.
  *
  * <p>Kept past the end of the condition it would silence the next one: the same fault returning
  * would arrive already marked as seen, by somebody who saw a different one.
  */
 @Test void theSameFaultComingBackIsNotAlreadyAcknowledged(){
  reports("ERROR");
  String key="VEHICLE_ERROR:"+OHT;
  alarms.acknowledge(SITE,key,"현장 확인 중","mgr1");
  assertThat(about(key)).singleElement().satisfies(a->assertThat(a.acknowledged()).isTrue());

  // Fixed. The line goes, and the acknowledgement has nothing left to be about.
  reports("IDLE");
  assertThat(about(key)).isEmpty();
  assertThat(alarms.retireAcknowledgementsOfEndedConditions(SITE)).isPositive();

  // It happens again. Nobody has seen this one.
  reports("ERROR");
  assertThat(about(key)).singleElement().satisfies(a->
   assertThat(a.acknowledged()).as("a different occurrence, seen by nobody").isFalse());
 }

 /** Keys identify the thing, so the same condition on the same vehicle is one line and not two. */
 @Test void keysAreStableSoNothingIsListedTwice(){
  reports("ERROR");
  var keys=alarms.alarms(SITE).stream().map(Alarm::key).toList();
  assertThat(keys).doesNotHaveDuplicates();
  assertThat(alarms.alarms(SITE).stream().map(Alarm::key).toList())
   .as("asking again gives the same answer").isEqualTo(keys);
 }
}
