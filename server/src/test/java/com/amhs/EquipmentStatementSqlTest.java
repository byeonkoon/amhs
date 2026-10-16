package com.amhs;
import com.amhs.equipment.EquipmentStates;
import com.amhs.equipment.infrastructure.EquipmentLinks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

/**
 * The one thing this controller says to a piece of equipment.
 *
 * <p>It knows something the machine does not: a vehicle has been sent to one of its ports. The
 * machine finds out when the vehicle turns up, and a stocker told a minute earlier can hold the
 * port and have the carrier ready. That statement is all that is written -- the handover handshake
 * is the vehicle's and the port's own business over their own wires.
 *
 * <p>What is proved here is what makes writing to a register block safe at all: the values go in
 * before the request bit, acceptance is matched to the statement by its own echo, and a statement
 * nobody acknowledged is said again -- which is allowed precisely because saying it twice says the
 * same thing, where a movement command said twice is a second movement.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class EquipmentStatementSqlTest {
 @Autowired EquipmentLinks devices; @Autowired EquipmentStates states; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1;
 private static final long PROFILE=9521, PROTOCOL=9521, WIRED=9521;
 private long unitId, portId, otherPortId, orderId;
 private String portCode;

 /**
  * A controller that speaks MC 3E binary and can be told how to behave about a statement.
  *
  * <p>It answers batch reads with the words it holds and takes batch writes into a block of its
  * own, remembering every write in order -- which is how "the values went in before the request
  * bit" stops being a claim about code and becomes a claim about the wire. When it is set to take
  * statements it does what the document asks of it: raises its own bit and echoes back the
  * sequence it was given.
  */
 private static final class FakeMelsec implements AutoCloseable {
  private static final int READ_BASE=1000, WRITE_BASE=1100;
  final ServerSocket server=new ServerSocket(0);
  final int[] read=new int[64];
  /** Every block ever written to it, in order, as the registers stood on arrival. */
  final List<int[]> writes=Collections.synchronizedList(new ArrayList<>());
  /** Whether it plays a controller that acknowledges. */
  volatile boolean takes=true;
  private final Thread thread;

  FakeMelsec() throws IOException {
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
   if(readShort(in)!=0x5000) throw new IOException("not a 3E request");
   in.readNBytes(5);
   var body=new byte[readShort(in)];
   in.readFully(body);
   int command=(body[2]&0xFF)|(body[3]&0xFF)<<8;
   int head=(body[6]&0xFF)|(body[7]&0xFF)<<8|(body[8]&0xFF)<<16;
   int points=(body[10]&0xFF)|(body[11]&0xFF)<<8;
   byte[] data=new byte[0];
   if(command==0x0401){
    data=new byte[points*2];
    for(int i=0;i<points;i++){
     int word=read[head-READ_BASE+i];
     data[i*2]=(byte)word; data[i*2+1]=(byte)(word>>8);
    }
   }else if(command==0x1401){
    var block=new int[points];
    for(int i=0;i<points;i++) block[i]=(body[12+i*2]&0xFF)|(body[13+i*2]&0xFF)<<8;
    writes.add(block);
    // The document's own handshake, played straight: the request bit is word 6 bit 15, the
    // sequence is word 4, and taking it means raising word 5 bit 15 with that sequence echoed.
    if(takes&&(block[6]&0x8000)!=0){ read[7]=block[4]; read[5]|=0x8000; }
   }
   writeShort(out,0xD000);
   out.writeByte(0x00); out.writeByte(0xFF); writeShort(out,0x03FF); out.writeByte(0x00);
   writeShort(out,data.length+2);
   writeShort(out,0);
   out.write(data); out.flush();
  }
  private static int readShort(DataInputStream in) throws IOException {
   int low=in.readUnsignedByte(); return low|in.readUnsignedByte()<<8;
  }
  private static void writeShort(DataOutputStream out,int value) throws IOException {
   out.writeByte(value); out.writeByte(value>>8);
  }
  void name(int at,String text){
   var bytes=java.util.Arrays.copyOf(text.getBytes(StandardCharsets.US_ASCII),8);
   for(int i=bytes.length;i-->text.length();) bytes[i]=' ';
   for(int i=0;i<4;i++) read[at+i]=(bytes[i*2]&0xFF)<<8|(bytes[i*2+1]&0xFF);
  }
  /** The statements it was given, newest last, as this controller's own words. */
  List<Said> said(){
   synchronized(writes){ return writes.stream().filter(w->(w[6]&0x8000)!=0).map(Said::of).toList(); }
  }
  int port(){ return server.getLocalPort(); }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 /** One statement as it arrived on the wire. */
 private record Said(String port,int sequence,int direction,boolean reserved,boolean transferring,
                     boolean attention){
  static Said of(int[] block){
   var bytes=new byte[8];
   for(int i=0;i<4;i++){ bytes[i*2]=(byte)(block[i]>>8); bytes[i*2+1]=(byte)block[i]; }
   return new Said(new String(bytes,StandardCharsets.US_ASCII).trim(),block[4],block[5],
    (block[6]&0b001)!=0,(block[6]&0b010)!=0,(block[6]&0b100)!=0);
  }
 }

 @BeforeEach void anOrderOnItsWayToAPort(){
  unitId=jdbc.queryForObject("SELECT u.id FROM equipment.unit u"
   +" WHERE u.site_id=? AND EXISTS (SELECT 1 FROM topology.port p"
   +"  JOIN topology.map_revision m ON m.id=p.revision_id AND m.status='ACTIVE'"
   +"  WHERE p.equipment_unit_id=u.id) ORDER BY u.id LIMIT 1",Long.class,SITE);
  var ports=jdbc.query("SELECT p.id,p.code FROM topology.port p"
   +" JOIN topology.map_revision m ON m.id=p.revision_id AND m.status='ACTIVE'"
   +" WHERE p.site_id=? AND p.equipment_unit_id=? ORDER BY p.id",
   (r,n)->new Object[]{r.getLong(1),r.getString(2)},SITE,unitId);
  portId=(Long)ports.getFirst()[0]; portCode=(String)ports.getFirst()[1];
  otherPortId=jdbc.queryForObject("SELECT p.id FROM topology.port p"
   +" JOIN topology.map_revision m ON m.id=p.revision_id AND m.status='ACTIVE'"
   +" WHERE p.site_id=? AND p.id<>? ORDER BY p.id LIMIT 1",Long.class,SITE,portId);
  clean();
 }

 @AfterEach void clean(){
  jdbc.update("DELETE FROM equipment.communication WHERE id=? OR (site_id=? AND unit_id=?)",WIRED,SITE,unitId);
  jdbc.update("DELETE FROM equipment.device_state WHERE unit_id=?",unitId);
  // The statements this test left. Another test's unit would otherwise be told, on its first pass,
  // that a port it has never heard of is clear.
  jdbc.update("DELETE FROM equipment.port_intent WHERE unit_id=?",unitId);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  if(orderId>0){
   jdbc.update("DELETE FROM transport.order_event WHERE order_id=?",orderId);
   jdbc.update("DELETE FROM transport.assignment WHERE order_id=?",orderId);
   jdbc.update("DELETE FROM transport.order WHERE id=?",orderId);
   orderId=0;
  }
  // Not @Transactional -- the link works on its own connection -- so the mode is put back by hand.
  jdbc.update("UPDATE platform.site_operation SET run_mode='SIMULATION',mode='MAINTENANCE' WHERE site_id=?",SITE);
 }

 private void wire(int port){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PLC','1.0','EQUIPMENT-MC-EXAMPLE',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO equipment.communication(id,site_id,unit_id,connection_profile_id,protocol_profile_id,"
   +"external_unit_id,enabled) VALUES(?,?,?,?,?,'STK-01',TRUE)",WIRED,SITE,unitId,PROFILE,PROTOCOL);
 }
 private void inOperation(){
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=?",SITE);
 }
 /** An order of this test's own, straight to the ledger: what is proved is the statement. */
 private void orderTo(String status,boolean toThisPort){
  orderId=jdbc.queryForObject("INSERT INTO transport.order(site_id,revision_id,request_source,"
   +"idempotency_key,request_fingerprint,source_port_id,destination_port_id,vehicle_type,carrier_code,"
   +"priority,status) VALUES(?,(SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'),"
   +"'statement-test',?,repeat('0',64),?,?,'OHT','CAR-STMT',50,?) RETURNING id",
   Long.class,SITE,SITE,"statement-test-"+System.nanoTime(),
   toThisPort?portId:otherPortId,toThisPort?otherPortId:portId,status);
 }
 private String stateOf(long port){
  var rows=jdbc.query("SELECT state FROM equipment.port_intent WHERE port_id=?",(r,n)->r.getString(1),port);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<100;i++){
   devices.poll();
   if(until.getAsBoolean()) return true;
   Thread.sleep(20);
  }
  return false;
 }

 /**
  * A vehicle on its way to a port is said to that port's equipment, once, and taken.
  *
  * <p>"Once" is the part worth proving. The statement is written again only when what it says
  * changes, so a port with a carrier coming is not a controller being written to five times a
  * second for the whole journey.
  */
 @Test void aportWithACarrierComingIsToldAndNotToldAgain() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;          // READY, able, taking calls
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);

   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).as("the controller took it").isTrue();
   var said=melsec.said();
   assertThat(said).hasSize(1);
   assertThat(said.getFirst().port()).isEqualTo(portCode);
   assertThat(said.getFirst().reserved()).isTrue();
   assertThat(said.getFirst().direction()).as("PICKUP, as the document's table writes it").isEqualTo(1);
   assertThat(said.getFirst().transferring()).as("on its way, not handing over yet").isFalse();
   assertThat(said.getFirst().sequence()).isEqualTo(1);

   for(int i=0;i<5;i++){ devices.poll(); Thread.sleep(10); }
   assertThat(melsec.said()).as("nothing changed, so nothing was said again").hasSize(1);
  }
 }

 /** The values go in before the request bit, which is the only order that is always safe. */
 @Test void thevaluesGoInBeforeTheRequestBit() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();

   var all=List.copyOf(melsec.writes);
   assertThat(all).as("values, then the bit, then the bit cleared").hasSizeGreaterThanOrEqualTo(2);
   assertThat(all.get(0)[6]&0x8000).as("the first write does not ask for anything").isZero();
   assertThat(Said.of(all.get(0)).port()).as("but it carries the statement").isEqualTo(portCode);
   assertThat(all.get(1)[6]&0x8000).as("the second is the request").isNotZero();
  }
 }

 /** Handing over now is a different statement from being on the way, and is said as one. */
 @Test void whenTheHandoverStartsTheMachineIsToldThatToo() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();

   jdbc.update("UPDATE transport.order SET status='LOADING' WHERE id=?",orderId);
   assertThat(waitFor(()->melsec.said().size()>1)).as("it was said again").isTrue();
   var said=melsec.said();
   var latest=said.getLast();
   assertThat(latest.transferring()).isTrue();
   assertThat(latest.reserved()).isTrue();
   assertThat(latest.sequence()).as("a statement of its own, so the echo cannot be the one before: %s",said)
    .isEqualTo(2);
  }
 }

 /**
  * A port that has nothing coming any more is told so.
  *
  * <p>Without this the machine would hold a port for a carrier that was cancelled an hour ago, and
  * nothing would ever contradict it.
  */
 @Test void aportWithNothingComingIsToldThatAsWell() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();

   jdbc.update("UPDATE transport.order SET status='COMPLETED' WHERE id=?",orderId);
   assertThat(waitFor(()->melsec.said().size()>1&&!melsec.said().getLast().reserved()))
    .as("the port was released").isTrue();
   var latest=melsec.said().getLast();
   assertThat(latest.port()).isEqualTo(portCode);
   assertThat(latest.direction()).as("nothing is coming, so no direction").isZero();
  }
 }

 /**
  * Work waiting for a person keeps the port, and says so.
  *
  * <p>A cancel that has to be confirmed, or a recovery, leaves the carrier wherever the handover
  * got to. Releasing the port under it is how a carrier ends up somewhere nobody looks, so the
  * statement is read from the stage the order was in when it stopped -- and it carries the fact
  * that somebody has to come and look.
  */
 @Test void aportHeldByWorkWaitingForAPersonStaysHeld() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   inOperation();
   orderTo("LOADING",true);
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();

   // As the ledger would leave it: the order moved out of LOADING into a cancel it must confirm.
   jdbc.update("UPDATE transport.order SET status='CANCEL_REQUESTED' WHERE id=?",orderId);
   jdbc.update("INSERT INTO transport.order_event(order_id,seq,old_status,new_status,reason)"
    +" VALUES(?,1,'LOADING','CANCEL_REQUESTED','test')",orderId);

   assertThat(waitFor(()->melsec.said().size()>1)).isTrue();
   var latest=melsec.said().getLast();
   assertThat(latest.reserved()).as("the carrier may be sitting in it").isTrue();
   assertThat(latest.attention()).as("and somebody has to come and look").isTrue();
   assertThat(latest.transferring()).as("nothing is moving while it waits").isFalse();
  }
 }

 /**
  * A statement nobody acknowledged is in doubt, and is said again.
  *
  * <p>This is the difference between a statement and a command, and the whole reason writing to
  * equipment can be this simple: the second telling says exactly what the first said. A movement
  * command repeated into a register that still holds it is a second movement; "a carrier is coming
  * to this port" repeated is the same sentence.
  */
 @Test void astatementNobodyAcknowledgedIsSaidAgainAndSaysTheSameThing() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   melsec.takes=false;                             // written, and never answered for
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);

   assertThat(waitFor(()->"IN_DOUBT".equals(stateOf(portId)))).as("nobody can say it was taken").isTrue();
   assertThat(waitFor(()->melsec.said().size()>1)).as("so it is said again").isTrue();
   var said=melsec.said();
   assertThat(said.getFirst().port()).isEqualTo(said.get(1).port());
   assertThat(said.getFirst().reserved()).isEqualTo(said.get(1).reserved());
   assertThat(said.getFirst().direction()).isEqualTo(said.get(1).direction());

   // And once it does answer, the doubt is settled rather than carried for ever.
   melsec.takes=true;
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();
  }
 }

 /** A simulated site tells real equipment nothing: the vehicle on its way does not exist. */
 @Test void asimulatedSiteSaysNothingToARealMachine() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   orderTo("TO_SOURCE",true);                      // the site is left in SIMULATION

   for(int i=0;i<10;i++){ devices.poll(); Thread.sleep(10); }
   assertThat(melsec.said()).as("nothing was said").isEmpty();
   assertThat(stateOf(portId)).isNull();
   // The reading still happens: taking account of a machine costs it nothing.
   assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment.device_state WHERE unit_id=?",
    Integer.class,unitId)).isEqualTo(1);
  }
 }

 /** What was said is on the screen, because this is the only place it can be seen. */
 @Test void whatWasSaidIsOnTheScreen() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.read[4]=1; melsec.read[5]=0b11;
   wire(melsec.port());
   inOperation();
   orderTo("TO_SOURCE",true);
   assertThat(waitFor(()->"TOLD".equals(stateOf(portId)))).isTrue();

   var shown=states.told(SITE).stream().filter(s->s.portId()==portId).findFirst().orElseThrow();
   assertThat(shown.portCode()).isEqualTo(portCode);
   assertThat(shown.reserved()).isTrue();
   assertThat(shown.direction()).isEqualTo("PICKUP");
   assertThat(shown.carrier()).isEqualTo("CAR-STMT");
   assertThat(shown.state()).isEqualTo("TOLD");
   assertThat(shown.toldAt()).isNotNull();
  }
 }

 /** Nothing listening is nothing written, and the port is recorded as not told. */
 @Test void withNothingListeningThePortIsRecordedAsNotTold() throws Exception {
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  wire(dead);
  inOperation();
  orderTo("TO_SOURCE",true);
  assertThat(waitFor(()->"NOT_TOLD".equals(stateOf(portId)))).isTrue();
  var shown=states.told(SITE).stream().filter(s->s.portId()==portId).findFirst().orElseThrow();
  assertThat(shown.lastError()).contains("not connected");
  assertThat(shown.toldAt()).as("nothing was written, so nothing was told").isNull();
 }
}
