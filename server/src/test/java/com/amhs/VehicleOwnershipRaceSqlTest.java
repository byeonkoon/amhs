package com.amhs;
import com.amhs.fleet.*;
import com.amhs.traffic.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * New work and a move out of a zone, decided in two real transactions at the same time, never both
 * take the same vehicle. Each side holds its transaction open while the other runs, so the other
 * either waits on the vehicle lock or reads before the commit; either way it must see the winner.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class VehicleOwnershipRaceSqlTest {
 @Autowired ZoneEvacuation evacuation; @Autowired TransportAssignment assignment; @Autowired TransportOrders orders;
 @Autowired TransportLedger ledger; @Autowired FleetState fleet; @Autowired TrafficControl traffic;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;

 private static final long SITE=1, REVISION=1, OHT=1, BAY_PORT=122;
 private final ExecutorService other=Executors.newSingleThreadExecutor();

 @BeforeEach @AfterEach void clean(){
  jdbc.update("UPDATE transport.assignment SET released_at=now() WHERE released_at IS NULL");
  jdbc.update("UPDATE transport.relocation SET finished_at=now(),finish_reason='TEST' WHERE finished_at IS NULL");
  jdbc.update("DELETE FROM traffic.reservation_wait");
  jdbc.update("DELETE FROM traffic.node_reservation");
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE status IN ('PENDING','DISPATCHING','FAILED')");
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=(id=?),home_node_id=NULL WHERE site_id=?",OHT,SITE);
 }
 @AfterEach void stop(){
  other.shutdownNow();
  jdbc.update("UPDATE fleet.vehicle_profile SET assignment_enabled=FALSE WHERE site_id=?",SITE);
 }

 /** Finished at the bay port, idle, holding only where it stands. */
 private void finishedInTheBay(){
  tx.executeWithoutResult(s->{
   fleet.report(SITE,REVISION,OHT,new VehicleReport(BAY_PORT,true,true,true,false,"SIMULATED"));
   traffic.request(SITE,REVISION,OHT,null,List.of(BAY_PORT));
  });
 }
 /** An order that starts where the vehicle stands, so it is the obvious candidate. */
 private long orderFromTheBay(){
  return tx.execute(s->orders.accept(SITE,new OrderRequest("RACE",UUID.randomUUID().toString(),"OHT-E","OHT-C",UUID.randomUUID().toString(),50),
   "test",null).order().id());
 }
 private boolean hasOrder(){ return ledger.assignedVehicleIds(SITE).contains(OHT); }
 private boolean isRelocating(){ return ledger.relocatingVehicleIds(SITE).contains(OHT); }

 @Test void whileAMoveOutIsBeingWrittenNewWorkDoesNotTakeTheVehicle() throws Exception {
  finishedInTheBay();
  long id=orderFromTheBay();
  var assigning=new AtomicReferenceFuture();
  tx.executeWithoutResult(s->{
   assertThat(evacuation.evacuateAll(SITE)).anyMatch(step->step.vehicleId()==OHT&&step.decision().equals("EVACUATING"));
   // Still uncommitted: dispatch runs now, in its own transaction.
   assigning.future=other.submit(()->tx.execute(t->assignment.assign(SITE,id,"test",null)));
   pause();
  });
  var thrown=catchThrowable(()->assigning.future.get(20,TimeUnit.SECONDS));
  assertThat(thrown).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TransportRejected.class);
  assertThat(isRelocating()).isTrue();
  assertThat(hasOrder()).isFalse();
 }

 @Test void whileNewWorkIsBeingAssignedAMoveOutDoesNotTakeTheVehicle() throws Exception {
  finishedInTheBay();
  long id=orderFromTheBay();
  var evacuating=new AtomicReferenceFuture();
  tx.executeWithoutResult(s->{
   assertThat(assignment.assign(SITE,id,"test",null).created()).isTrue();
   evacuating.future=other.submit(()->tx.execute(t->evacuation.evacuateAll(SITE)));
   pause();
  });
  @SuppressWarnings("unchecked")
  var steps=(List<ZoneEvacuation.Step>)evacuating.future.get(20,TimeUnit.SECONDS);
  assertThat(steps).anyMatch(step->step.vehicleId()==OHT&&step.decision().equals("HAS_ORDER"));
  assertThat(hasOrder()).isTrue();
  assertThat(isRelocating()).isFalse();
 }

 /** Gives the other transaction time to reach the vehicle lock; the outcome does not depend on it. */
 private static void pause(){
  try{ Thread.sleep(500); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
 }
 private static final class AtomicReferenceFuture { volatile Future<?> future; }
}
