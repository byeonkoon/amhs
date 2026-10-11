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
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A byte protocol over the same TCP link, laid out by a mapping document.
 *
 * <p>The frame is fixed at 32 bytes with a checksum, so the two things worth proving are that what
 * goes out is exactly what the document describes -- down to the offset of the second node -- and
 * that a frame whose checksum does not add up is refused rather than read. A corrupted node number
 * read anyway is a vehicle driving somewhere nobody asked for.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class PcFrameVehicleLinkSqlTest {
 @Autowired @Qualifier("vehicleChannels") DeviceChannel channels;
 @Autowired CommandOutbox outbox; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2;
 private static final long PROFILE=9401, PROTOCOL=9401, LINKED=9401;
 private static final int SIZE=32;

 /** A controller that speaks 32-byte frames: it keeps what it is given and answers what it is told to. */
 private static final class FakeController implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0);
  final BlockingQueue<byte[]> heard=new LinkedBlockingQueue<>();
  private volatile OutputStream out;
  private final boolean ack;
  private final Thread thread;
  FakeController(boolean ack) throws IOException {
   this.ack=ack;
   thread=Thread.ofVirtual().start(()->{
    try(Socket s=server.accept();var in=new DataInputStream(s.getInputStream())){
     out=s.getOutputStream();
     var frame=new byte[SIZE];
     while(true){
      in.readFully(frame);
      heard.add(frame.clone());
      if(ack) send(answer(0x90,frame[2],frame[3],0,0,0));
     }
    }catch(IOException ignored){ }
   });
  }
  int port(){ return server.getLocalPort(); }
  void send(byte[] frame) throws IOException {
   for(int i=0;i<100&&out==null;i++){ try{ Thread.sleep(10); }catch(InterruptedException e){ Thread.currentThread().interrupt(); } }
   out.write(frame); out.flush();
  }
  /** A frame the document would recognise, with the checksum it insists on. */
  static byte[] answer(int command,byte seqHigh,byte seqLow,int status,int node,int battery){
   var frame=new byte[SIZE];
   frame[0]=0x02; frame[SIZE-1]=0x03;
   frame[1]=(byte)command; frame[2]=seqHigh; frame[3]=seqLow;
   // A report says which vehicle it is about, in the slot the document reads the identity from.
   var who="AGV-7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
   System.arraycopy(who,0,frame,4,who.length);
   for(int i=4+who.length;i<12;i++) frame[i]=(byte)' ';
   frame[25]=(byte)status;
   frame[26]=(byte)(node>>8); frame[27]=(byte)node;
   frame[28]=(byte)(battery>>8); frame[29]=(byte)battery;
   int xor=0;
   for(int i=1;i<=29;i++) xor^=frame[i]&0xFF;
   frame[30]=(byte)xor;
   return frame;
  }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id=?",LINKED);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE command_id LIKE 'pc-test-%'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
 }
 private void wire(int port){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','PC-FRAME-EXAMPLE',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','AGV-7',TRUE)",
   LINKED,SITE,AGV1,PROFILE,PROTOCOL);
 }
 private com.amhs.integration.DeviceCommand command(String type,String payload){
  String id="pc-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,AGV1,id,type,payload));
 }

 @Test void thecommandGoesOutExactlyWhereTheDocumentPutsIt() throws Exception {
  try(var controller=new FakeController(true)){
   wire(controller.port());
   var verdict=channels.send(command("MOVE","{\"authorizedNodeIds\":[203,204]}"));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   var frame=controller.heard.poll(3,TimeUnit.SECONDS);
   assertThat(frame).as("what the controller heard").isNotNull();
   assertThat(frame[0]).as("STX").isEqualTo((byte)0x02);
   assertThat(frame[SIZE-1]).as("ETX").isEqualTo((byte)0x03);
   assertThat(frame[1]&0xFF).as("command byte").isEqualTo(0x10);
   assertThat(new String(frame,4,8).trim()).as("the vehicle as the far side knows it").isEqualTo("AGV-7");
   assertThat(frame[12]&0xFF).as("how many nodes").isEqualTo(2);
   assertThat((frame[13]&0xFF)<<8|(frame[14]&0xFF)).as("first node").isEqualTo(203);
   assertThat((frame[15]&0xFF)<<8|(frame[16]&0xFF)).as("second node").isEqualTo(204);
   int xor=0;
   for(int i=1;i<=29;i++) xor^=frame[i]&0xFF;
   assertThat(frame[30]&0xFF).as("the checksum it carries").isEqualTo(xor);
  }
 }

 @Test void withoutAnAnswerItIsInDoubt() throws Exception {
  try(var controller=new FakeController(false)){
   wire(controller.port());
   var verdict=channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.IN_DOUBT);
   assertThat(controller.heard.poll(3,TimeUnit.SECONDS)).as("it did go out").isNotNull();
  }
 }

 @Test void whatTheControllerReportsBecomesAReport() throws Exception {
  try(var controller=new FakeController(true)){
   wire(controller.port());
   channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));
   controller.send(FakeController.answer(0x81,(byte)0,(byte)0,1,207,615));
   var seen=waitFor(()->fleet.find(SITE,REVISION,AGV1).filter(v->"DEVICE".equals(v.source())
    &&v.batteryPct()!=null).isPresent());
   assertThat(seen).as("the frame reached the fleet").isTrue();
   var v=fleet.find(SITE,REVISION,AGV1).orElseThrow();
   assertThat(v.nodeId()).isEqualTo(207L);
   assertThat(v.effectiveStatus()).isEqualTo("MOVING");
   assertThat(v.batteryPct()).isEqualTo(61.5);
  }
 }

 @Test void aframeWhoseCheckSumIsWrongIsNotRead() throws Exception {
  try(var controller=new FakeController(true)){
   wire(controller.port());
   channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));
   var corrupted=FakeController.answer(0x81,(byte)0,(byte)0,1,207,615);
   corrupted[27]=(byte)(corrupted[27]+1);        // the node number, and now the checksum is wrong
   controller.send(corrupted);
   Thread.sleep(400);
   assertThat(fleet.find(SITE,REVISION,AGV1).flatMap(v->java.util.Optional.ofNullable(v.nodeId())))
    .as("nothing was taken from a frame that does not add up").isEmpty();
  }
 }

 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<150;i++){ if(until.getAsBoolean()) return true; Thread.sleep(20); }
  return false;
 }
}
