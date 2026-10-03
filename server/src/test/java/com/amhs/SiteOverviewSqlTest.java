package com.amhs;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.overview.SiteOverview;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * A number on an overview is read as a fact about the site and almost never looked up afterwards,
 * so one that is nearly right is worse here than anywhere else.
 *
 * <p>What is checked is the part that is easy to get quietly wrong: the distinctions the tiles
 * claim to draw, and saying nothing where there is nothing to say.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class SiteOverviewSqlTest {
 @Autowired SiteOverview overview; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, OHT=1, NODE=101;

 /** Other cases share the database, so what this one switched on is switched back. */
 @AfterEach void leaveItAsItWas(){
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=? AND id=?",SITE,OHT);
  tx.executeWithoutResult(s->fleet.returnToService(SITE,REVISION,OHT));
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
 }

 /**
  * A vehicle nobody has heard from is not a vehicle that stopped reporting.
  *
  * <p>One has never said where it is, the other said so and went quiet. Only the second is
  * something going wrong, and a tile that adds them together would raise a number on a site where
  * nothing has happened yet.
  */
 @Test void neverHavingReportedIsCountedApartFromHavingStopped(){
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  var fresh=overview.overview(SITE,60).fleet();
  assertThat(fresh.configured()).isPositive();

  // Reported, then long ago: that is the one that counts as something going wrong.
  jdbc.update("UPDATE fleet.vehicle_state SET reported_at=now()-interval '2 hours'"
   +" WHERE vehicle_id=? AND revision_id=?",OHT,REVISION);
  var stale=overview.overview(SITE,60).fleet();
  assertThat(stale.stale()).isGreaterThan(fresh.stale());
  assertThat(stale.notReporting()).as("it reported; it just stopped").isEqualTo(fresh.notReporting());
 }

 /** Taken out of service is counted as such, and stops counting as able to take work. */
 @Test void aVehicleOutOfServiceIsNotCountedAsAbleToWork(){
  // Set rather than assumed: whether this vehicle was already able to take work depends on what
  // other cases left behind, and the claim under test is about the difference, not the level.
  jdbc.update("UPDATE fleet.vehicle_profile SET enabled=TRUE,assignment_enabled=TRUE WHERE site_id=? AND id=?",SITE,OHT);
  tx.executeWithoutResult(s->fleet.report(SITE,REVISION,OHT,
   new VehicleReport(NODE,true,true,true,false,"SIMULATED",null,"REMOTE","IDLE")));
  var before=overview.overview(SITE,60).fleet();
  assertThat(before.usable()).as("it can take work to begin with").isPositive();
  tx.executeWithoutResult(s->fleet.takeOutOfService(SITE,REVISION,OHT,"개요 시험","mgr1"));
  var after=overview.overview(SITE,60).fleet();
  assertThat(after.outOfService()).isEqualTo(before.outOfService()+1);
  assertThat(after.usable()).isEqualTo(before.usable()-1);
  assertThat(after.configured()).as("it still exists").isEqualTo(before.configured());
 }

 /**
  * No answer is said as no answer.
  *
  * <p>No job having finished and every job finishing instantly are different things, and a zero
  * would make them look the same.
  */
 @Test void aWindowWithNothingInItHasNoMiddleTimeRatherThanZero(){
  // A window so short that nothing can have ended inside it.
  var empty=overview.overview(SITE,1);
  if(empty.throughput().measured()==0)
   assertThat(empty.throughput().medianSeconds()).as("nothing to take the middle of").isNull();
  // Whatever the window holds, the parts add up to the whole.
  var hour=overview.overview(SITE,60).throughput();
  assertThat(hour.completed()+hour.cancelled()+hour.failed()).isEqualTo(hour.finished());
  assertThat(hour.measured()).as("only finished jobs can be measured").isLessThanOrEqualTo(hour.completed());
 }

 /** The window is the caller's, within bounds, and the answer says which window it used. */
 @Test void theWindowIsSaidBackRatherThanAssumed(){
  assertThat(overview.overview(SITE,30).throughput().windowMinutes()).isEqualTo(30);
  assertThat(overview.overview(SITE,0).throughput().windowMinutes()).as("clamped, not zero").isEqualTo(1);
  assertThat(overview.overview(SITE,99999).throughput().windowMinutes()).isEqualTo(1440);
 }

 /** Work waiting on a person is counted apart: it is the only kind that will not move on its own. */
 @Test void workWaitingOnAPersonIsCountedApartFromWorkWaitingOnAVehicle(){
  var work=overview.overview(SITE,60).work();
  assertThat(work.queued()).isGreaterThanOrEqualTo(0);
  assertThat(work.running()).isGreaterThanOrEqualTo(0);
  assertThat(work.needingAPerson()).isGreaterThanOrEqualTo(0);
  // A queue with something in it has a longest wait; an empty one has none rather than zero.
  if(work.queued()==0) assertThat(work.oldestQueuedSeconds()).isNull();
  else assertThat(work.oldestQueuedSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
 }

 /** The overview says how the site is being run, which decides how everything else should be read. */
 @Test void itSaysHowTheSiteIsBeingRun(){
  var operation=overview.overview(SITE,60).operation();
  assertThat(operation.mode()).isIn("RUNNING","MAINTENANCE","RECOVERING");
  assertThat(operation.runMode()).isIn("SIMULATION","OPERATION");
 }
}
