package com.amhs;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The first real link to a vehicle: one TCP connection, one JSON object per line.
 *
 * <p>What is proved here is the contract the gateway settles commands on -- nothing written is
 * NOT_DELIVERED and may be sent again, acknowledged is DELIVERED, and written-without-an-answer is
 * IN_DOUBT and must not be -- and that a report coming the other way is written to the fleet the
 * same as any other. A socket in this test stands in for the controller in front of a line.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.device.tcp.ack-timeout-ms=400",
 "amhs.device.tcp.connect-timeout-ms=400",
 "amhs.device.tcp.retry-after-ms=0"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class VehicleLinkSqlTest {
 @Autowired @Qualifier("jsonLineVehicleLinks") DeviceChannel link;
 @Autowired CommandOutbox outbox; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2;
 private static final long PROFILE=9101, PROTOCOL=9101, LINKED=9101;

 /** The far side: accepts one connection, hands every line it reads to the test, writes what it is told. */
 private static final class FarSide implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0);
  final BlockingQueue<String> heard=new LinkedBlockingQueue<>();
  private volatile BufferedWriter out;
  private final Thread thread;
  FarSide(boolean answer) throws IOException {
   thread=Thread.ofVirtual().start(()->{
    try(Socket s=server.accept();
        var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8))){
     out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8));
     String line;
     while((line=in.readLine())!=null){
      heard.add(line);
      if(answer&&line.contains("\"type\":\"command\"")){
       String id=line.replaceAll(".*\"commandId\":\"([^\"]+)\".*","$1");
       say("{\"type\":\"ack\",\"commandId\":\""+id+"\"}");
      }
     }
    }catch(IOException ignored){ /* the test closes it */ }
   });
  }
  int port(){ return server.getLocalPort(); }
  void say(String frame) throws IOException {
   for(int i=0;i<100&&out==null;i++){ try{ Thread.sleep(10); }catch(InterruptedException e){ Thread.currentThread().interrupt(); } }
   out.write(frame); out.write("\n"); out.flush();
  }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id=?",LINKED);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE command_id LIKE 'link-test-%'");
 }

 /** A vehicle wired to a controller on this port, the way configuration would leave it. */
 private void wire(int port,boolean enabled){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,?,'CLIENT',0)",PROFILE,SITE,port,enabled);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','JSON_LINE_V1',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,protocol_profile_id,"
   +"manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','V-01',?)",LINKED,SITE,AGV1,PROFILE,PROTOCOL,enabled);
 }
 private com.amhs.integration.DeviceCommand command(String type,String payload){
  String id="link-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,AGV1,id,type,payload));
 }

 @Test void acommandTheFarSideAcknowledgesIsDelivered() throws Exception {
  try(var far=new FarSide(true)){
   wire(far.port(),true);
   var verdict=link.send(command("MOVE","{\"authorizedNodeIds\":[203,204]}"));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   String frame=far.heard.poll(2,TimeUnit.SECONDS);
   assertThat(frame).as("what the far side heard").isNotNull()
    .contains("\"type\":\"command\"").contains("\"vehicle\":\"V-01\"").contains("\"command\":\"MOVE\"")
    .contains("203");
  }
 }

 @Test void withNothingListeningNothingWasWrittenAndItMayBeSentAgain() throws Exception {
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  wire(dead,true);
  var verdict=link.send(command("MOVE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).isEqualTo("LINK_REFUSED");
 }

 @Test void writtenAndUnansweredIsInDoubt() throws Exception {
  try(var far=new FarSide(false)){
   wire(far.port(),true);
   var verdict=link.send(command("MOVE","{}"));
   assertThat(verdict.outcome()).as("it may be on the wire; nobody may say it is not").isEqualTo(DeviceChannel.Outcome.IN_DOUBT);
   assertThat(verdict.detail()).isEqualTo("ACK_TIMEOUT");
   assertThat(far.heard.poll(2,TimeUnit.SECONDS)).as("it did go out").isNotNull();
  }
 }

 @Test void aVehicleNobodyWiredIsNotOursToSendTo(){
  wire(9999,false);
  var verdict=link.send(command("MOVE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).isEqualTo("ADAPTER_NOT_CONFIGURED");
 }

 @Test void whatTheVehicleReportsIsWrittenToTheFleetLikeAnyOtherReport() throws Exception {
  try(var far=new FarSide(true)){
   wire(far.port(),true);
   // The link opens when there is something to send, so a command goes first.
   link.send(command("MOVE","{}"));
   far.say("{\"type\":\"report\",\"vehicle\":\"V-01\",\"nodeId\":207,\"mode\":\"REMOTE\",\"status\":\"MOVING\","
    +"\"carrier\":true,\"batteryPct\":61.5}");
   var seen=waitFor(()->fleet.find(SITE,REVISION,AGV1).filter(v->Long.valueOf(207L).equals(v.nodeId())).isPresent());
   assertThat(seen).as("the report reached the fleet").isTrue();
   var v=fleet.find(SITE,REVISION,AGV1).orElseThrow();
   assertThat(v.effectiveStatus()).isEqualTo("MOVING");
   assertThat(v.carrierPresent()).isTrue();
   assertThat(v.source()).as("a real vehicle said so, not the simulator").isEqualTo("DEVICE");
   assertThat(v.batteryPct()).isEqualTo(61.5);
  }
 }

 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<100;i++){ if(until.getAsBoolean()) return true; Thread.sleep(20); }
  return false;
 }
}
