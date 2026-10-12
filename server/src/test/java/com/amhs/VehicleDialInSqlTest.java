package com.amhs;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.VehicleListening;
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
 * The other way round: a gateway dials in to this controller.
 *
 * <p>A line behind a firewall, or one with no fixed address, cannot be dialled. It dials. What that
 * costs is that the socket arriving says nothing about whose it is, so the whole of this is about
 * identity: a connection speaks for a vehicle only once it has said which vehicle, a name this site
 * does not use buys nothing, and a vehicle nobody has dialled in for is one that nothing was
 * written to -- which is a command that may be sent again, not one in doubt.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class VehicleDialInSqlTest {
 @Autowired @Qualifier("jsonLineVehicleLinks") DeviceChannel link;
 @Autowired VehicleListening listening;
 @Autowired CommandOutbox outbox; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2, AGV2=3;
 private static final long PROFILE=9301, PROTOCOL=9301, LINKED=9301, LINKED2=9302;

 /** A gateway: dials in, says which vehicles it speaks for, acknowledges what it is sent. */
 private static final class Gateway implements AutoCloseable {
  final Socket socket; final BlockingQueue<String> heard=new LinkedBlockingQueue<>();
  private final BufferedWriter out; private final Thread thread;
  /** Counted down when the reading ends, which is the honest signal that the far end let go. */
  private final CountDownLatch gone=new CountDownLatch(1);
  Gateway(int port,boolean answer) throws IOException {
   socket=new Socket("127.0.0.1",port);
   socket.setTcpNoDelay(true);
   out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
   thread=Thread.ofVirtual().start(()->{
    try(var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8))){
     String line;
     while((line=in.readLine())!=null){
      heard.add(line);
      if(answer&&line.contains("\"type\":\"command\"")){
       String id=line.replaceAll(".*\"commandId\":\"([^\"]+)\".*","$1");
       say("{\"type\":\"ack\",\"commandId\":\""+id+"\"}");
      }
     }
    }catch(IOException ignored){ /* the test closes it */ }
    finally{ gone.countDown(); }
   });
  }
  void say(String frame) throws IOException { out.write(frame); out.write("\n"); out.flush(); }
  /** Says which vehicle it speaks for, with nothing else to report. */
  void hello(String vehicle) throws IOException {
   say("{\"type\":\"hello\",\"vehicle\":\""+vehicle+"\"}");
  }
  boolean wasLetGo() throws InterruptedException { return gone.await(3,TimeUnit.SECONDS); }
  public void close() throws IOException { socket.close(); thread.interrupt(); }
 }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id IN (?,?)",LINKED,LINKED2);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE command_id LIKE 'dial-in-test-%'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id IN (?,?)",SITE,AGV1,AGV2);
  // Configuration decides which ports are open, so putting the rows back decides it both ways.
  listening.listen();
 }

 /** A free port, picked the only way a port can be known to be free. */
 private static int freePort() throws IOException {
  try(var probe=new ServerSocket(0)){ return probe.getLocalPort(); }
 }

 /** A profile this controller listens on, with one vehicle the far side calls V-01. */
 private void wire(int port){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'SERVER',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','JSON_LINE_V1',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,protocol_profile_id,"
   +"manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','V-01',TRUE)",LINKED,SITE,AGV1,PROFILE,PROTOCOL);
  listening.listen();
 }
 /** A second vehicle on the same profile, so one gateway can speak for both. */
 private void alsoWire(){
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,protocol_profile_id,"
   +"manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','V-02',TRUE)",LINKED2,SITE,AGV2,PROFILE,PROTOCOL);
 }
 private com.amhs.integration.DeviceCommand command(long vehicleId,String type,String payload){
  String id="dial-in-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,vehicleId,id,type,payload));
 }
 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<150;i++){ if(until.getAsBoolean()) return true; Thread.sleep(20); }
  return false;
 }
 /** Waits until a command to this vehicle reaches somebody, which is how the claim becomes visible. */
 private DeviceChannel.Verdict sendOnceConnected(long vehicleId) throws InterruptedException {
  DeviceChannel.Verdict verdict=null;
  for(int i=0;i<150;i++){
   verdict=link.send(command(vehicleId,"MOVE","{\"authorizedNodeIds\":[203]}"));
   if(!"VEHICLE_NOT_CONNECTED".equals(verdict.detail())) return verdict;
   Thread.sleep(20);
  }
  return verdict;
 }

 @Test void withNobodyDialledInNothingWasWrittenAndItMayBeSentAgain() throws Exception {
  wire(freePort());
  var verdict=link.send(command(AGV1,"MOVE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).as("nothing was written, so it is repeatable").isEqualTo("VEHICLE_NOT_CONNECTED");
 }

 @Test void agatewayThatSaysWhoItIsGetsThatVehiclesCommands() throws Exception {
  int port=freePort();
  wire(port);
  try(var gateway=new Gateway(port,true)){
   gateway.hello("V-01");
   var verdict=sendOnceConnected(AGV1);
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   String frame=gateway.heard.poll(2,TimeUnit.SECONDS);
   assertThat(frame).as("what the gateway heard").isNotNull()
    .contains("\"type\":\"command\"").contains("\"vehicle\":\"V-01\"").contains("\"command\":\"MOVE\"");
  }
 }

 /** One connection, two vehicles: a gateway speaks for a line, not for one machine. */
 @Test void onegatewayMaySpeakForSeveralVehicles() throws Exception {
  int port=freePort();
  wire(port); alsoWire();
  try(var gateway=new Gateway(port,true)){
   gateway.hello("V-01"); gateway.hello("V-02");
   assertThat(sendOnceConnected(AGV1).outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   assertThat(sendOnceConnected(AGV2).outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   var frames=new java.util.ArrayList<String>();
   for(int i=0;i<2;i++) frames.add(gateway.heard.poll(2,TimeUnit.SECONDS));
   assertThat(frames).anyMatch(f->f!=null&&f.contains("\"vehicle\":\"V-01\""))
    .anyMatch(f->f!=null&&f.contains("\"vehicle\":\"V-02\""));
  }
 }

 @Test void whatadialledInGatewayReportsIsWrittenToTheFleet() throws Exception {
  int port=freePort();
  wire(port);
  try(var gateway=new Gateway(port,true)){
   gateway.say("{\"type\":\"report\",\"vehicle\":\"V-01\",\"nodeId\":203,\"mode\":\"REMOTE\",\"status\":\"IDLE\","
    +"\"carrier\":true,\"batteryPct\":72.5}");
   var arrived=waitFor(()->fleet.find(SITE,REVISION,AGV1)
    .filter(v->"DEVICE".equals(v.source())&&v.batteryPct()!=null).isPresent());
   assertThat(arrived).as("the report reached the fleet").isTrue();
   var v=fleet.find(SITE,REVISION,AGV1).orElseThrow();
   assertThat(v.mode()).isEqualTo("REMOTE");
   assertThat(v.carrierPresent()).isTrue();
   assertThat(v.batteryPct()).isEqualTo(72.5);
   // Reporting about a vehicle is saying you speak for it, so the command goes down this socket.
   assertThat(sendOnceConnected(AGV1).outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
  }
 }

 /** A connection naming something this site does not use is not a connection to keep. */
 @Test void anameThisProfileDoesNotUseIsRefusedAndTheConnectionGoes() throws Exception {
  int port=freePort();
  wire(port);
  try(var gateway=new Gateway(port,false)){
   gateway.hello("SOMEBODY-ELSE");
   assertThat(gateway.wasLetGo()).as("the controller let it go").isTrue();
   // And nothing it said bought it anything: the vehicle it was not is still unconnected.
   var verdict=link.send(command(AGV1,"MOVE","{}"));
   assertThat(verdict.detail()).isEqualTo("VEHICLE_NOT_CONNECTED");
  }
 }

 /**
  * A gateway that restarted is on the new socket. Taking the vehicle away from the old one is the
  * only answer that leaves it reachable; refusing the new one would strand it behind a dead line.
  */
 @Test void asecondConnectionForTheSameVehicleTakesItOver() throws Exception {
  int port=freePort();
  wire(port);
  try(var first=new Gateway(port,true)){
   first.hello("V-01");
   assertThat(sendOnceConnected(AGV1).outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   assertThat(first.heard.poll(2,TimeUnit.SECONDS)).isNotNull();
   try(var second=new Gateway(port,true)){
    second.hello("V-01");
    // The old connection is let go once it speaks for nothing.
    assertThat(first.wasLetGo()).as("the first connection was let go").isTrue();
    assertThat(sendOnceConnected(AGV1).outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
    assertThat(second.heard.poll(2,TimeUnit.SECONDS)).as("the new connection has it").isNotNull();
   }
  }
 }

 /**
  * The byte dialect dials in too, and says who it is the only way it can.
  *
  * <p>A frame has no field called "vehicle"; it has the slot the document calls the identity. That
  * the two dialects arrive at the same claim by different roads is the thing worth proving -- it is
  * what lets a gateway speaking either one be put behind the same port.
  */
 @Test void aframeGatewayIsIdentifiedByTheSlotTheDocumentCallsTheIdentity() throws Exception {
  int port=freePort();
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'SERVER',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','PC-FRAME-EXAMPLE',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','AGV-7',TRUE)",
   LINKED,SITE,AGV1,PROFILE,PROTOCOL);
  listening.listen();

  try(var socket=new Socket("127.0.0.1",port)){
   socket.setTcpNoDelay(true);
   // A status frame about AGV-7: standing at node 203, idle, 61.0% battery -- which the document
   // reads in tenths, so that is what goes on the wire.
   socket.getOutputStream().write(frameAbout("AGV-7",203,610));
   socket.getOutputStream().flush();
   var arrived=waitFor(()->fleet.find(SITE,REVISION,AGV1)
    .filter(v->"DEVICE".equals(v.source())&&v.batteryPct()!=null).isPresent());
   assertThat(arrived).as("the frame reached the fleet").isTrue();
   assertThat(fleet.find(SITE,REVISION,AGV1).orElseThrow().batteryPct()).isEqualTo(61.0);

   // Having said which vehicle it is about, the connection is that vehicle's to send to.
   var verdict=sendOnceConnected(AGV1);
   assertThat(verdict.outcome()).as("written, and nothing answered it").isEqualTo(DeviceChannel.Outcome.IN_DOUBT);
   assertThat(verdict.detail()).isEqualTo("ACK_TIMEOUT");
   var out=new byte[32];
   new DataInputStream(socket.getInputStream()).readFully(out);
   assertThat(out[0]).as("STX").isEqualTo((byte)0x02);
   assertThat(new String(out,4,8,StandardCharsets.US_ASCII).trim()).isEqualTo("AGV-7");
  }
 }

 /** A 32-byte status frame the shipped example document can read, checksum and all. */
 private static byte[] frameAbout(String vehicle,int node,int batteryTenths){
  var frame=new byte[32];
  frame[0]=0x02; frame[31]=0x03;
  frame[1]=(byte)0x81;                  // a status frame; 0x90 is an acknowledgement
  var who=vehicle.getBytes(StandardCharsets.US_ASCII);
  System.arraycopy(who,0,frame,4,who.length);
  for(int i=4+who.length;i<12;i++) frame[i]=(byte)' ';
  frame[26]=(byte)(node>>8); frame[27]=(byte)node;
  frame[28]=(byte)(batteryTenths>>8); frame[29]=(byte)batteryTenths;
  int xor=0;
  for(int i=1;i<=29;i++) xor^=frame[i]&0xFF;
  frame[30]=(byte)xor;
  return frame;
 }

 /** Switching the profile off closes the port, which is what switching it off has to mean. */
 @Test void aprofileNoLongerInUseStopsBeingListenedOn() throws Exception {
  int port=freePort();
  wire(port);
  try(var gateway=new Gateway(port,true)){ gateway.hello("V-01"); }
  jdbc.update("UPDATE integration.connection_profile SET enabled=FALSE WHERE id=?",PROFILE);
  listening.listen();
  assertThatThrownBy(()->{ try(var refused=new Gateway(port,false)){ refused.hello("V-01"); } })
   .isInstanceOf(IOException.class);
 }
}
