package com.amhs;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The vehicle link over MQTT, against a broker running inside the test.
 *
 * <p>What a mapping document promises is only worth what the wire does with it, so this is the
 * whole way through: a command becomes the message the document describes, the broker carries it,
 * and a state message coming back becomes a report on the vehicle's own row -- with the maker's
 * words translated into this controller's.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class MqttVehicleLinkSqlTest {
 @Autowired @Qualifier("vehicleChannels") DeviceChannel channels;
 @Autowired CommandOutbox outbox; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2;
 private static final long PROFILE=9201, PROTOCOL=9201, LINKED=9201;
 private static Server broker; private static int port;

 @BeforeAll static void startBroker() throws Exception {
  try(var free=new ServerSocket(0)){ port=free.getLocalPort(); }
  var config=new Properties();
  config.setProperty("port",String.valueOf(port));
  config.setProperty("host","127.0.0.1");
  config.setProperty("websocket_port","0");
  config.setProperty("allow_anonymous","true");
  config.setProperty("persistence_enabled","false");
  broker=new Server();
  broker.startServer(new MemoryConfig(config));
 }
 @AfterAll static void stopBroker(){ if(broker!=null) broker.stopServer(); }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id=?",LINKED);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE command_id LIKE 'mqtt-test-%'");
  // The vehicle starts from nothing, so a row another test left behind cannot be mistaken for
  // this one's report arriving.
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
 }

 /** A vehicle on this broker, named the way VDA5050 names one, with the shipped mapping. */
 private void wire(int brokerPort){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'MQTT','127.0.0.1',?,TRUE,'BROKER',0)",PROFILE,SITE,brokerPort);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'VDA5050','3.0.0','VDA5050-V3',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,protocol_profile_id,"
   +"manufacturer,external_vehicle_id,serial_number,interface_name,enabled)"
   +" VALUES(?,?,?,?,?,'ACME','AGV-7','AGV-7','uagv',TRUE)",LINKED,SITE,AGV1,PROFILE,PROTOCOL);
 }
 private com.amhs.integration.DeviceCommand command(String type,String payload){
  String id="mqtt-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,AGV1,id,type,payload));
 }
 /** Somebody else on the broker, standing in for the vehicle. Paho refuses to close while connected,
  * so the handle disconnects first and the test does not have to remember to. */
 private record Vehicle(MqttClient client) implements AutoCloseable {
  public void close() throws MqttException { if(client.isConnected()) client.disconnect(); client.close(); }
 }
 private Vehicle vehicle() throws MqttException {
  var client=new MqttClient("tcp://127.0.0.1:"+port,"vehicle-"+System.nanoTime(),new MemoryPersistence());
  var options=new MqttConnectOptions();
  options.setCleanSession(true); options.setConnectionTimeout(2);
  client.connect(options);
  return new Vehicle(client);
 }

 @Test void thesampleMappingIsInUse(){
  assertThat(Files.exists(Path.of("../docs/sample-mappings/vda5050-v3.json"))).isTrue();
 }

 @Test void acommandBecomesTheMessageTheMappingDescribes() throws Exception {
  wire(port);
  var heard=new LinkedBlockingQueue<String>();
  try(var vehicle=vehicle()){
   vehicle.client().subscribe("uagv/v3/ACME/AGV-7/order",1,(topic,message)->heard.add(new String(message.getPayload(),StandardCharsets.UTF_8)));
   var verdict=channels.send(command("MOVE","{\"authorizedNodeIds\":[203,204]}"));
   assertThat(verdict.outcome()).as("the broker took it").isEqualTo(DeviceChannel.Outcome.DELIVERED);
   String message=heard.poll(5,TimeUnit.SECONDS);
   assertThat(message).as("what the vehicle heard").isNotNull()
    .contains("\"serialNumber\":\"AGV-7\"").contains("\"version\":\"3.0.0\"").contains("\"nodeId\":\"203\"");
  }
 }

 @Test void whatTheVehicleReportsBecomesAReportInThisControllersWords() throws Exception {
  wire(port);
  try(var vehicle=vehicle()){
   // The link is opened by having something to send, which is also when it starts listening.
   channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));
   vehicle.client().publish("uagv/v3/ACME/AGV-7/state",
    ("{\"lastNodeId\":\"AGV-M-007\",\"operatingMode\":\"AUTOMATIC\",\"driving\":true,"
     +"\"loads\":[{\"loadId\":\"C1\"}],\"batteryState\":{\"batteryCharge\":61.5}}").getBytes(StandardCharsets.UTF_8),1,false);
   // One state message lands as two writes -- the row, then the battery -- so waiting on the row
   // alone can see it half applied. Waiting for the last of them is waiting for the report.
   var arrived=waitFor(()->fleet.find(SITE,REVISION,AGV1)
    .filter(v->"DEVICE".equals(v.source())&&v.batteryPct()!=null).isPresent());
   assertThat(arrived).as("the state message reached the fleet").isTrue();
   var v=fleet.find(SITE,REVISION,AGV1).orElseThrow();
   // AUTOMATIC is the maker's word; REMOTE is this controller's, and the document is what joins them.
   assertThat(v.mode()).isEqualTo("REMOTE");
   assertThat(v.carrierPresent()).isTrue();
   assertThat(v.source()).isEqualTo("DEVICE");
   assertThat(v.batteryPct()).isEqualTo(61.5);
  }
 }

 @Test void withNoBrokerThereNothingWasPublished(){
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  catch(Exception e){ throw new IllegalStateException(e); }
  wire(dead);
  var verdict=channels.send(command("MOVE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).isEqualTo("BROKER_REFUSED");
 }

 @Test void acommandTheMappingDoesNotDescribeIsSaidRatherThanDropped(){
  wire(port);
  var verdict=channels.send(command("MORE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).isEqualTo("COMMAND_NOT_IN_MAPPING");
 }

 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<150;i++){ if(until.getAsBoolean()) return true; Thread.sleep(20); }
  return false;
 }
}
