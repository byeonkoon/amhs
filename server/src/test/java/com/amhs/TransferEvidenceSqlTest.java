package com.amhs;
import com.amhs.transport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Where the carrier is, is never taken on trust: finishing a pickup or a drop needs evidence of where
 * it went, trying again needs evidence that it did not move, and a person's word needs a person.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TransferEvidenceSqlTest {
 @Autowired TransportOrders orders; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
 private static final long SITE=1;
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";

 @BeforeEach void limit(){ jdbc.update("UPDATE platform.site_operation SET transfer_max_attempts=3 WHERE site_id=?",SITE); }

 private long atPickup(){
  long id=orders.accept(SITE,new OrderRequest("EVIDENCE",UUID.randomUUID().toString(),"OHT-A","OHT-C","C",50),"test",null).order().id();
  for(var s:List.of(OrderStatus.ASSIGNED,OrderStatus.TO_SOURCE,OrderStatus.LOADING)) orders.advance(SITE,id,s,"test","test",null);
  return id;
 }
 private TransportOrder move(long id,OrderStatus to,TransitionEvidence evidence){
  return orders.advance(SITE,id,to,"test","test",null,evidence);
 }
 private static TransitionEvidence sensor(CarrierLocation at){ return TransitionEvidence.sensor(at); }
 private static void rejected(ThrowingCallable call,String code){
  assertThatThrownBy(call).isInstanceOfSatisfying(TransportRejected.class,e->assertThat(e.code()).isEqualTo(code));
 }
 private interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {}
 private Map<String,Object> lastEvent(long id){
  return jdbc.queryForMap("SELECT new_status,evidence_kind,command_id,carrier_location,actor_code FROM transport.order_event"
   +" WHERE order_id=? ORDER BY seq DESC LIMIT 1",id);
 }

 @Test void aPickupIsFinishedOnlyOnEvidenceThatTheCarrierIsOnBoard(){
  long id=atPickup();
  rejected(()->orders.advance(SITE,id,OrderStatus.TO_DESTINATION,"test","test",null),"PICKUP_EVIDENCE_REQUIRED");
  // The system's own bookkeeping cannot say where a carrier is, whatever it claims.
  rejected(()->move(id,OrderStatus.TO_DESTINATION,new TransitionEvidence(TransitionEvidence.Kind.SYSTEM,null,CarrierLocation.VEHICLE)),
   "PICKUP_EVIDENCE_REQUIRED");
  rejected(()->move(id,OrderStatus.TO_DESTINATION,sensor(CarrierLocation.SOURCE)),"PICKUP_EVIDENCE_REQUIRED");
  var after=move(id,OrderStatus.TO_DESTINATION,new TransitionEvidence(TransitionEvidence.Kind.SENSOR_TRANSITION,"cmd-pick-1",CarrierLocation.VEHICLE));
  assertThat(after.status()).isEqualTo(OrderStatus.TO_DESTINATION);
  assertThat(after.carrierLocation()).isEqualTo(CarrierLocation.VEHICLE);
  assertThat(lastEvent(id)).containsEntry("evidence_kind","SENSOR_TRANSITION").containsEntry("command_id","cmd-pick-1")
   .containsEntry("carrier_location","VEHICLE");
 }

 @Test void aDropIsFinishedOnlyOnEvidenceThatTheCarrierIsAtTheDestination(){
  long id=atPickup();
  move(id,OrderStatus.TO_DESTINATION,sensor(CarrierLocation.VEHICLE));
  orders.advance(SITE,id,OrderStatus.UNLOADING,"test","test",null);
  rejected(()->orders.advance(SITE,id,OrderStatus.COMPLETED,"test","test",null),"DROP_EVIDENCE_REQUIRED");
  rejected(()->move(id,OrderStatus.COMPLETED,sensor(CarrierLocation.VEHICLE)),"DROP_EVIDENCE_REQUIRED");
  var done=move(id,OrderStatus.COMPLETED,new TransitionEvidence(TransitionEvidence.Kind.ACTION_FEEDBACK,"cmd-drop-1",CarrierLocation.DESTINATION));
  assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(done.carrierLocation()).isEqualTo(CarrierLocation.DESTINATION);
 }

 @Test void aPickupThatDidNotHappenIsTriedAgainUntilTheLimitThenGoesToRecovery(){
  jdbc.update("UPDATE platform.site_operation SET transfer_max_attempts=2 WHERE site_id=?",SITE);
  long id=atPickup();
  // Trying again needs the sensor's word that the carrier never left the source.
  rejected(()->orders.advance(SITE,id,OrderStatus.TO_SOURCE,"test","test",null),"RETRY_EVIDENCE_REQUIRED");
  rejected(()->move(id,OrderStatus.TO_SOURCE,sensor(CarrierLocation.VEHICLE)),"RETRY_EVIDENCE_REQUIRED");
  assertThat(move(id,OrderStatus.TO_SOURCE,sensor(CarrierLocation.SOURCE)).status()).isEqualTo(OrderStatus.TO_SOURCE);
  orders.advance(SITE,id,OrderStatus.LOADING,"test","test",null);
  // The second failure reaches the limit of two attempts.
  var exhausted=move(id,OrderStatus.TO_SOURCE,sensor(CarrierLocation.SOURCE));
  assertThat(exhausted.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
  assertThat(exhausted.carrierLocation()).isEqualTo(CarrierLocation.SOURCE);
  assertThat(orders.events(SITE,id).getLast().reason()).startsWith("TRANSFER_ATTEMPTS_EXHAUSTED");
 }

 @Test void aDropThatDidNotHappenIsTriedAgainWithTheCarrierStillOnBoard(){
  long id=atPickup();
  move(id,OrderStatus.TO_DESTINATION,sensor(CarrierLocation.VEHICLE));
  orders.advance(SITE,id,OrderStatus.UNLOADING,"test","test",null);
  rejected(()->move(id,OrderStatus.TO_DESTINATION,sensor(CarrierLocation.DESTINATION)),"RETRY_EVIDENCE_REQUIRED");
  assertThat(move(id,OrderStatus.TO_DESTINATION,sensor(CarrierLocation.VEHICLE)).status()).isEqualTo(OrderStatus.TO_DESTINATION);
 }

 @Test void aPersonsWordNeedsAnIdentifiedPerson(){
  long id=atPickup();
  var manual=new TransitionEvidence(TransitionEvidence.Kind.MANUAL_CONFIRMED,null,CarrierLocation.VEHICLE);
  rejected(()->orders.advance(SITE,id,OrderStatus.TO_DESTINATION,"saw it","api",null,manual),"EVIDENCE_NEEDS_PRINCIPAL");
  rejected(()->orders.advance(SITE,id,OrderStatus.TO_DESTINATION,"saw it",null,null,manual),"EVIDENCE_NEEDS_PRINCIPAL");
  assertThat(orders.advance(SITE,id,OrderStatus.TO_DESTINATION,"saw it","Admin",null,manual).status())
   .isEqualTo(OrderStatus.TO_DESTINATION);
 }

 @Test void aCancelConfirmedWithTheCarrierBackAtTheSourceEndsTheOrder(){
  long id=atPickup();
  orders.advance(SITE,id,OrderStatus.CANCEL_REQUESTED,"test","test",null);
  var cancelled=move(id,OrderStatus.CANCELLED,sensor(CarrierLocation.SOURCE));
  assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
  assertThat(cancelled.carrierLocation()).isEqualTo(CarrierLocation.SOURCE);
 }

 @Test void progressIsReportedOverHttpAndRecordedAgainstWhoeverPassedTheGate() throws Exception {
  long id=atPickup();
  String body="{\"status\":\"TO_DESTINATION\",\"reason\":\"on board\",\"evidence\":"
   +"{\"kind\":\"MANUAL_CONFIRMED\",\"carrierLocation\":\"VEHICLE\"}}";
  mvc.perform(post("/api/v1/sites/1/transport-orders/"+id+"/transitions").contentType("application/json").content(body))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/sites/1/transport-orders/"+id+"/transitions").header("Authorization",TOKEN)
    .contentType("application/json").content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TO_DESTINATION"))
   .andExpect(jsonPath("$.carrierLocation").value("VEHICLE"));
  assertThat(lastEvent(id)).containsEntry("actor_code","local-dev").containsEntry("evidence_kind","MANUAL_CONFIRMED");
  mvc.perform(post("/api/v1/sites/1/transport-orders/"+id+"/transitions").header("Authorization",TOKEN)
    .contentType("application/json").content("{\"status\":\"UNLOADING\",\"evidence\":{\"kind\":\"GUESS\"}}"))
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_EVIDENCE_KIND"));
 }
}
