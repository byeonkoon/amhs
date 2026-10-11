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
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

/**
 * The link to a controller that is a PLC, against a Modbus server standing in for one.
 *
 * <p>What has to be right here is not the wire format but the handshake. A register keeps what was
 * written to it, so a command counts as taken only when the controller raises its own bit and
 * echoes the sequence it took; and when it does not, the values are sitting in its registers and
 * nobody may say the command did not arrive. Both of those are checked.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class PlcVehicleLinkSqlTest {
 @Autowired @Qualifier("vehicleChannels") DeviceChannel channels;
 @Autowired CommandOutbox outbox; @Autowired FleetState fleet;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1, REVISION=1, AGV1=2;
 private static final long PROFILE=9301, PROTOCOL=9301, LINKED=9301;
 /** The sample document: write block 40100, read block 40200, 16 words each. */
 private static final int WRITE_AT=99, READ_AT=199, WORDS=16;

 /**
  * A Modbus TCP server with one block written to it and one block read from it.
  *
  * <p>It answers function 3 and function 16 and nothing else, which is all a controller interface
  * uses. What it does with what it is given is the test's to decide: by default it behaves as a
  * controller that takes the command -- raises its accepted bit and echoes the sequence.
  */
 private static final class FakePlc implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0);
  final int[] written=new int[256], shown=new int[256];
  final AtomicBoolean accept=new AtomicBoolean(true);
  private final Thread thread;
  FakePlc() throws IOException {
   thread=Thread.ofVirtual().start(()->{
    while(!server.isClosed()){
     try(Socket s=server.accept();
         var in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
         var out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))){
      while(true) serve(in,out);
     }catch(IOException ignored){ /* the next connection, or the test closing */ }
    }
   });
  }
  private void serve(DataInputStream in,DataOutputStream out) throws IOException {
   int id=in.readUnsignedShort(); in.readUnsignedShort(); int length=in.readUnsignedShort(); in.readUnsignedByte();
   var pdu=new byte[length-1];
   in.readFully(pdu);
   int function=pdu[0]&0xFF;
   byte[] answer;
   if(function==3){
    int at=(pdu[1]&0xFF)<<8|(pdu[2]&0xFF), count=(pdu[3]&0xFF)<<8|(pdu[4]&0xFF);
    answer=new byte[2+count*2];
    answer[0]=3; answer[1]=(byte)(count*2);
    for(int i=0;i<count;i++){ answer[2+i*2]=(byte)(shown[at+i]>>8); answer[3+i*2]=(byte)shown[at+i]; }
   }else if(function==16){
    int at=(pdu[1]&0xFF)<<8|(pdu[2]&0xFF), count=(pdu[3]&0xFF)<<8|(pdu[4]&0xFF);
    for(int i=0;i<count;i++) written[at+i]=(pdu[6+i*2]&0xFF)<<8|(pdu[7+i*2]&0xFF);
    took(at);
    answer=new byte[]{16,pdu[1],pdu[2],pdu[3],pdu[4]};
   }else answer=new byte[]{(byte)(function|0x80),1};
   out.writeShort(id); out.writeShort(0); out.writeShort(answer.length+1); out.writeByte(1);
   out.write(answer); out.flush();
  }
  /** What a controller does when it sees the request bit: take the command, and say which one. */
  private void took(int at){
   boolean requested=(written[WRITE_AT+10]&1)==1;
   if(!requested||!accept.get()) return;
   shown[READ_AT]=1;                       // accepted bit
   shown[READ_AT+1]=written[WRITE_AT+1];   // the sequence it took, echoed back
  }
  int port(){ return server.getLocalPort(); }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id=?",LINKED);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN' WHERE command_id LIKE 'plc-test-%'");
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=? AND vehicle_id=?",SITE,AGV1);
 }

 private void wire(int port){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PLC','1.0','PLC-WORD-EXAMPLE',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','1',TRUE)",
   LINKED,SITE,AGV1,PROFILE,PROTOCOL);
 }
 private com.amhs.integration.DeviceCommand command(String type,String payload){
  String id="plc-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,AGV1,id,type,payload));
 }

 @Test void acommandTheControllerTakesIsDelivered() throws Exception {
  try(var plc=new FakePlc()){
   wire(plc.port());
   var verdict=channels.send(command("MOVE","{\"authorizedNodeIds\":[203,204]}"));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   // The values are where the document says they are: command 16 (MOVE) and the nodes after it.
   assertThat(plc.written[WRITE_AT]).as("command word").isEqualTo(16);
   assertThat(plc.written[WRITE_AT+2]).as("first node").isEqualTo(203);
   assertThat(plc.written[WRITE_AT+3]).as("second node").isEqualTo(204);
   assertThat(plc.written[WRITE_AT+10]&1).as("the request bit is put back down afterwards").isZero();
  }
 }

 @Test void withoutTheControllersAcknowledgementItIsInDoubtAndNotRepeated() throws Exception {
  try(var plc=new FakePlc()){
   plc.accept.set(false);          // the controller never raises its bit
   wire(plc.port());
   var verdict=channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));
   // The values are in its registers. Nobody may say the command did not arrive.
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.IN_DOUBT);
   assertThat(verdict.detail()).isEqualTo("PLC_NOT_ACKNOWLEDGED");
   assertThat(plc.written[WRITE_AT]).as("it was written all the same").isEqualTo(16);
  }
 }

 @Test void withNoControllerThereNothingWasWritten() throws Exception {
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  wire(dead);
  var verdict=channels.send(command("MOVE","{}"));
  assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
  assertThat(verdict.detail()).isEqualTo("PLC_NOT_CONNECTED");
 }

 @Test void whatTheRegistersShowBecomesAReport() throws Exception {
  try(var plc=new FakePlc()){
   wire(plc.port());
   plc.shown[READ_AT+2]=1;      // status: moving
   plc.shown[READ_AT+3]=207;    // node
   plc.shown[READ_AT+4]=615;    // battery, a tenth of a percent each
   plc.shown[READ_AT+5]=1;      // carrier bit
   channels.send(command("MOVE","{\"authorizedNodeIds\":[203]}"));   // opens the link and starts the polling
   var seen=waitFor(()->fleet.find(SITE,REVISION,AGV1).filter(v->"DEVICE".equals(v.source())
    &&v.batteryPct()!=null).isPresent());
   assertThat(seen).as("the register block reached the fleet").isTrue();
   var v=fleet.find(SITE,REVISION,AGV1).orElseThrow();
   assertThat(v.nodeId()).isEqualTo(207L);
   assertThat(v.effectiveStatus()).isEqualTo("MOVING");
   assertThat(v.batteryPct()).isEqualTo(61.5);
   assertThat(v.carrierPresent()).isTrue();
  }
 }

 @Test void acommandTheDocumentDoesNotDescribeIsSaidRatherThanWritten() throws Exception {
  try(var plc=new FakePlc()){
   wire(plc.port());
   var verdict=channels.send(command("MORE","{}"));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   assertThat(verdict.detail()).isEqualTo("COMMAND_NOT_IN_MAPPING");
   assertThat(plc.written[WRITE_AT]).as("nothing went into the registers").isZero();
  }
 }

 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<150;i++){ if(until.getAsBoolean()) return true; Thread.sleep(20); }
  return false;
 }
}
