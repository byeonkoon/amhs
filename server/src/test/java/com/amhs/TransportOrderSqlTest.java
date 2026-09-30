package com.amhs;
import com.amhs.transport.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true","amhs.configuration.write-token=amhs-test-only-token-32-characters-long"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class TransportOrderSqlTest {
 private static final String TOKEN="Bearer amhs-test-only-token-32-characters-long";
 private static final String BASE="/api/v1/sites/1/transport-orders";
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired TransportOrders orders;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

 private Map<String,Object> body(String from,String to,String carrier){
  var m=new LinkedHashMap<String,Object>();
  m.put("sourcePortCode",from);m.put("destinationPortCode",to);
  if(carrier!=null)m.put("carrierCode",carrier);
  m.put("priority",50);
  return m;
 }
 private org.springframework.test.web.servlet.ResultActions post(String key,Map<String,Object> body)throws Exception{
  return mvc.perform(MockMvcRequestBuilders.post(BASE).header("Authorization",TOKEN)
   .header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsBytes(body)));
 }

 @Test void acceptedOrderStartsQueuedWithUnknownCarrierLocation()throws Exception{
  var res=post("key-basic",body("OHT-A","OHT-B","CST-1")).andExpect(status().isCreated()).andReturn();
  var created=json.readValue(res.getResponse().getContentAsByteArray(),TransportOrder.class);
  assertThat(created.status()).isEqualTo(OrderStatus.QUEUED);
  assertThat(created.carrierLocation()).isEqualTo(CarrierLocation.UNKNOWN);
  assertThat(created.startedAt()).isNull();
  assertThat(created.terminalAt()).isNull();
 }
 @Test void sameKeyAndPayloadReturnsTheSameOrderWithoutCreatingAnother()throws Exception{
  var first=json.readValue(post("key-replay",body("OHT-A","OHT-B","CST-2"))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  var second=json.readValue(post("key-replay",body("OHT-A","OHT-B","CST-2"))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  assertThat(second.id()).isEqualTo(first.id());
 }
 @Test void sameKeyWithDifferentPayloadIsAConflict()throws Exception{
  post("key-conflict",body("OHT-A","OHT-B","CST-3")).andExpect(status().isCreated());
  post("key-conflict",body("OHT-A","OHT-B","CST-DIFFERENT"))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
 }
 @Test void mixedAccessVehicleTypeIsRejectedAsSingleVehicleWork()throws Exception{
  post("key-mixed",body("OHT-A","AGV-A",null))
   .andExpect(status().isUnprocessableEntity())
   .andExpect(jsonPath("$.code").value("ACCESS_VEHICLE_TYPE_MISMATCH"));
 }
 @Test void structuralErrorsAreRejectedBeforeAnyOrderExists()throws Exception{
  post("key-unknown-port",body("NO-SUCH-PORT","OHT-B",null))
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SOURCE_PORT_NOT_FOUND"));
  post("key-same",body("OHT-A","OHT-A",null))
   .andExpect(status().isUnprocessableEntity());
  var outOfRange=body("OHT-A","OHT-B",null); outOfRange.put("priority",999);
  post("key-priority",outOfRange)
   .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PRIORITY_OUT_OF_RANGE"));
 }
 @Test void cancelBeforePickupEndsTheOrderAndRecordsTheReason()throws Exception{
  var created=json.readValue(post("key-cancel",body("OHT-A","OHT-B",null))
   .andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+created.id()+"/cancel")
    .header("Authorization",TOKEN).header("X-Amhs-Reason","line stopped"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
  var after=orders.find(1L,created.id()).orElseThrow();
  assertThat(after.terminalAt()).isNotNull();
  assertThat(after.cancelReason()).isEqualTo("line stopped");
 }
 @Test void cancelAfterPickupWaitsForDeviceConfirmation()throws Exception{
  var created=json.readValue(post("key-cancel-late",body("OHT-A","OHT-B",null))
   .andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  orders.advance(1L,created.id(),OrderStatus.ASSIGNED,"dispatched","test",null);
  orders.advance(1L,created.id(),OrderStatus.TO_SOURCE,"moving","test",null);
  orders.advance(1L,created.id(),OrderStatus.LOADING,"picking","test",null);
  mvc.perform(MockMvcRequestBuilders.post(BASE+"/"+created.id()+"/cancel").header("Authorization",TOKEN))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
  assertThat(orders.find(1L,created.id()).orElseThrow().terminalAt()).isNull();
 }
 @Test void cancellationAcknowledgementCannotLoseAnUnknownOrOnboardCarrier(){
  for(var location:List.of(CarrierLocation.UNKNOWN,CarrierLocation.VEHICLE)){
   var created=orders.accept(1,new OrderRequest("test","cancel-location-"+location,
    "OHT-A","OHT-B","CST",50),"test",null).order();
   for(var status:List.of(OrderStatus.ASSIGNED,OrderStatus.TO_SOURCE,OrderStatus.LOADING,OrderStatus.CANCEL_REQUESTED))
    orders.advance(1,created.id(),status,"prepare","test",null);
   // Model the last persisted observation; no adapter or physical device is involved.
   jdbc.update("UPDATE transport.order SET carrier_location=? WHERE id=?",location.name(),created.id());
   var before=orders.find(1,created.id()).orElseThrow();
   var after=orders.advance(1,created.id(),OrderStatus.CANCELLED,"device acknowledged","test","cancel-trace");
   assertThat(after.status()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
   assertThat(after.carrierLocation()).isEqualTo(location);
   assertThat(after.terminalAt()).isNull();
   assertThat(after.version()).isEqualTo(before.version()+1);
   var events=orders.events(1,created.id());
   assertThat(events).hasSize(6);
   assertThat(events.getLast().oldStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
   assertThat(events.getLast().newStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
   assertThat(events.getLast().reason()).isEqualTo("CANCEL_REQUIRES_CARRIER_RECOVERY: device acknowledged");
  }
 }
 @Test void forbiddenTransitionIsRejectedAndLeavesNoEvent()throws Exception{
  var created=json.readValue(post("key-skip",body("OHT-A","OHT-B",null))
   .andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  assertThatThrownBy(()->orders.advance(1L,created.id(),OrderStatus.COMPLETED,"skip","test",null))
   .isInstanceOf(TransportRejected.class);
  assertThat(orders.find(1L,created.id()).orElseThrow().status()).isEqualTo(OrderStatus.QUEUED);
  assertThat(orders.events(1L,created.id())).hasSize(1);
 }
 @Test void everyTransitionIsRecordedInOrderWithStartedAtSet()throws Exception{
  var created=json.readValue(post("key-events",body("OHT-A","OHT-B",null))
   .andReturn().getResponse().getContentAsByteArray(),TransportOrder.class);
  orders.advance(1L,created.id(),OrderStatus.ASSIGNED,"dispatched","test","trace-1");
  orders.advance(1L,created.id(),OrderStatus.TO_SOURCE,"moving","test","trace-2");
  var events=orders.events(1L,created.id());
  assertThat(events).extracting(OrderEventView::newStatus)
   .containsExactly(OrderStatus.QUEUED,OrderStatus.ASSIGNED,OrderStatus.TO_SOURCE);
  assertThat(events).extracting(OrderEventView::seq).containsExactly(1,2,3);
  assertThat(orders.find(1L,created.id()).orElseThrow().startedAt()).isNotNull();
 }
 @Test void listingFiltersByStatus()throws Exception{
  post("key-list",body("OHT-A","OHT-B",null)).andExpect(status().isCreated());
  mvc.perform(get(BASE+"?status=QUEUED&limit=10")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("QUEUED"));
  mvc.perform(get(BASE+"?status=NOT_A_STATUS")).andExpect(status().isUnprocessableEntity());
 }
}
