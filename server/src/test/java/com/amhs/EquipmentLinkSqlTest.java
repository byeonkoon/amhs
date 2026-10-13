package com.amhs;
import com.amhs.equipment.EquipmentCommunicationConfiguration;
import com.amhs.equipment.EquipmentCommunicationSettings;
import com.amhs.equipment.EquipmentStates;
import com.amhs.equipment.SimulatedEquipment;
import com.amhs.equipment.infrastructure.EquipmentLinks;
import com.amhs.integration.ConnectionConfiguration;
import com.amhs.integration.ConnectionSettings;
import com.amhs.integration.ProtocolConfiguration;
import com.amhs.integration.ProtocolSettings;
import com.amhs.platform.ConfigurationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/**
 * What a piece of equipment says, read off a MELSEC controller.
 *
 * <p>Until now a stocker had a state only in simulation: a site in OPERATION knew nothing about its
 * equipment, so transport asked whether a unit would take a carrier and, getting no answer, carried
 * on as though the answer were yes. What is proved here is the other half -- that the machine's own
 * answer arrives, that silence is not mistaken for an answer, and that transport acts on it.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class EquipmentLinkSqlTest {
 @Autowired EquipmentLinks devices; @Autowired SimulatedEquipment equipment;
 @Autowired EquipmentStates states; @Autowired EquipmentCommunicationConfiguration links;
 @Autowired ConnectionConfiguration connections; @Autowired ProtocolConfiguration protocols;
 @Autowired JdbcTemplate jdbc;
 private static final long SITE=1;
 private static final long PROFILE=9501, PROTOCOL=9501, WIRED=9501;
 private long unitId, portId;

 /**
  * A controller that speaks MC 3E binary: it answers a batch read with the words it is holding.
  *
  * <p>Only the one command, because reading is the whole of what this adapter does. A request for
  * anything else is answered with an end code, which is the controller saying no -- and which this
  * test uses to prove that a refusal is not mistaken for a reading.
  */
 private static final class FakeMelsec implements AutoCloseable {
  /** The device number the block starts at, as the example document asks for it: D1000. */
  private static final int BASE=1000;
  final ServerSocket server=new ServerSocket(0);
  final int[] words=new int[64];
  volatile int endCode=0;
  volatile boolean deaf=false;
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
   int subheader=readShort(in);
   if(subheader!=0x5000) throw new IOException("not a 3E request");
   in.readNBytes(5);                               // network, PC, I/O, station
   int length=readShort(in);
   var body=new byte[length];
   in.readFully(body);
   if(deaf) return;                                // holding the line open and saying nothing
   int command=(body[2]&0xFF)|(body[3]&0xFF)<<8;
   int head=(body[6]&0xFF)|(body[7]&0xFF)<<8|(body[8]&0xFF)<<16;
   int points=(body[10]&0xFF)|(body[11]&0xFF)<<8;
   byte[] data=new byte[0];
   if(endCode==0&&command==0x0401){
    data=new byte[points*2];
    for(int i=0;i<points;i++){
     int word=words[head-BASE+i];
     data[i*2]=(byte)word; data[i*2+1]=(byte)(word>>8);
    }
   }
   writeShort(out,0xD000);
   out.writeByte(0x00); out.writeByte(0xFF); writeShort(out,0x03FF); out.writeByte(0x00);
   writeShort(out,data.length+2);
   writeShort(out,endCode);
   out.write(data); out.flush();
  }
  private static int readShort(DataInputStream in) throws IOException {
   int low=in.readUnsignedByte(); return low|in.readUnsignedByte()<<8;
  }
  private static void writeShort(DataOutputStream out,int value) throws IOException {
   out.writeByte(value); out.writeByte(value>>8);
  }
  /** Puts an ASCII name across two bytes per word, the way the example document reads it. */
  void name(int at,String text){
   var bytes=java.util.Arrays.copyOf(text.getBytes(StandardCharsets.US_ASCII),8);
   for(int i=bytes.length;i-->text.length();) bytes[i]=' ';
   for(int i=0;i<4;i++) words[at+i]=(bytes[i*2]&0xFF)<<8|(bytes[i*2+1]&0xFF);
  }
  int port(){ return server.getLocalPort(); }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 @BeforeEach void wireUp(){
  clean();
  unitId=jdbc.queryForObject("SELECT u.id FROM equipment.unit u"
   +" JOIN topology.map_revision m ON m.id=u.revision_id AND m.status='ACTIVE'"
   +" WHERE u.site_id=? ORDER BY u.id LIMIT 1",Long.class,SITE);
  portId=jdbc.queryForObject("SELECT id FROM topology.port WHERE site_id=? AND equipment_unit_id=? LIMIT 1",
   Long.class,SITE,unitId);
 }
 @AfterEach void clean(){
  jdbc.update("DELETE FROM equipment.communication WHERE id=?",WIRED);
  if(unitId>0){
   jdbc.update("DELETE FROM equipment.communication WHERE site_id=? AND unit_id=?",SITE,unitId);
   jdbc.update("DELETE FROM equipment.device_state WHERE unit_id=?",unitId);
   jdbc.update("DELETE FROM equipment.simulation_state WHERE unit_id=?",unitId);
  }
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  // Profiles made through the services get ids of their own, so they are named to be found again.
  jdbc.update("DELETE FROM integration.connection_profile WHERE site_id=? AND port IN (2096,2097)",SITE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE site_id=? AND adapter_key IN (?,?)",
   SITE,"PC-FRAME-EXAMPLE","EQUIPMENT-MC-EXAMPLE");
  // This class is not @Transactional -- the link reads on its own connection -- so nothing rolls
  // back and every row and mode it touches has to be put back by hand. MAINTENANCE is what the
  // schema starts a site in, and what the rest of the suite expects to find.
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
 /** The site running for real, which is when the machine's own answer is the one that counts. */
 private void inOperation(){
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=?",SITE);
 }
 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<100;i++){
   devices.poll();
   if(until.getAsBoolean()) return true;
   Thread.sleep(20);
  }
  return false;
 }
 private String statusNow(){
  var rows=jdbc.query("SELECT status FROM equipment.device_state WHERE unit_id=?",(r,n)->r.getString(1),unitId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private Boolean onlineNow(){
  var rows=jdbc.query("SELECT online FROM equipment.device_state WHERE unit_id=?",(r,n)->r.getBoolean(1),unitId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 /**
  * Why transport would be refused this port right now, or null when it would not be.
  *
  * <p>Waited on rather than the state column, because this is the thing being proved. A reading
  * only matters through the answer it produces, and waiting on the column instead would race the
  * poll interval: reading a state and then asking would let a few slow polls turn a fault into a
  * silence between the two.
  */
 private String refusalFor(long port){
  try{ equipment.requireAvailable(SITE,port); return null; }
  catch(SimulatedEquipment.Prohibited e){ return e.getMessage(); }
 }

 @Test void whatTheMachineSaysIsWrittenWhereTheRestOfTheSystemLooks() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");                       // D1000..D1003: the name it is known by
   melsec.words[4]=2;                             // D1004: BUSY
   melsec.words[5]=0b11;                          // D1005: able, and taking calls
   wire(melsec.port());
   assertThat(waitFor(()->"BUSY".equals(statusNow()))).as("the reading arrived").isTrue();
   assertThat(onlineNow()).isTrue();
   var row=jdbc.queryForMap("SELECT available,call_enabled FROM equipment.device_state WHERE unit_id=?",unitId);
   assertThat(row.get("available")).isEqualTo(true);
   assertThat(row.get("call_enabled")).isEqualTo(true);
  }
 }

 /** The whole point of reading it: a stocker in fault is one transport must not send a carrier to. */
 @Test void transportIsRefusedByWhatTheMachineSaid() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.words[4]=9;                             // ERROR
   melsec.words[5]=0b11;
   wire(melsec.port());
   inOperation();
   assertThat(waitFor(()->"EQUIPMENT_IN_FAULT".equals(refusalFor(portId))))
    .as("the fault it reported is what refuses the port").isTrue();

   // And when it says it is well again, the same question is allowed.
   melsec.words[4]=1;                             // READY
   assertThat(waitFor(()->refusalFor(portId)==null)).as("it was let through again").isTrue();
  }
 }

 /** A unit that will not take a call is refused without being in fault at all. */
 @Test void aunitThatIsNotTakingCallsIsRefused() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.words[4]=1;                             // READY
   melsec.words[5]=0b01;                          // able, but not taking calls
   wire(melsec.port());
   inOperation();
   assertThat(waitFor(()->"EQUIPMENT_CALL_DISABLED".equals(refusalFor(portId)))).isTrue();
   assertThat(statusNow()).as("it is well, it just will not take one").isEqualTo("READY");
  }
 }

 /**
  * Silence is not "unchanged".
  *
  * <p>A controller that has gone on holding its last values would read as READY for ever. The one
  * answer that is honest about a machine nobody has heard from is that nobody has heard from it.
  */
 @Test void amachineThatGoesQuietGoesOfflineAndStopsBeingTrusted() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.words[4]=1;                             // READY
   melsec.words[5]=0b11;
   wire(melsec.port());
   inOperation();
   assertThat(waitFor(()->refusalFor(portId)==null)).as("a machine that answers is let through").isTrue();

   melsec.deaf=true;
   assertThat(waitFor(()->"EQUIPMENT_OFFLINE".equals(refusalFor(portId)))).as("it was noticed as gone").isTrue();
   assertThat(onlineNow()).isFalse();
   // What it last said is kept: what a machine was doing when it went quiet is the thing worth knowing.
   assertThat(statusNow()).isEqualTo("READY");
  }
 }

 /** Wired and never once heard from is not "fine so far". */
 @Test void wiredAndNeverHeardFromIsRefused() throws Exception {
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  wire(dead);
  inOperation();
  devices.poll();
  assertThatThrownBy(()->equipment.requireAvailable(SITE,portId))
   .isInstanceOf(SimulatedEquipment.Prohibited.class).hasMessage("EQUIPMENT_OFFLINE");
 }

 /** A controller answering "no" is an answer, and it is not a reading. */
 @Test void arefusalFromTheControllerIsNotMistakenForAReading() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.words[4]=1;
   melsec.endCode=0xC059;                         // the controller saying no to the request
   wire(melsec.port());
   inOperation();
   devices.poll(); Thread.sleep(100); devices.poll();
   assertThat(statusNow()).as("nothing was read, so nothing was written").isNull();
   assertThatThrownBy(()->equipment.requireAvailable(SITE,portId))
    .isInstanceOf(SimulatedEquipment.Prohibited.class).hasMessage("EQUIPMENT_OFFLINE");
  }
 }

 /** A site that has not described its equipment is not stopped by a fact nobody has stated. */
 @Test void aunitNobodyWiredIsNotRefused(){
  inOperation();
  assertThatCode(()->equipment.requireAvailable(SITE,portId)).doesNotThrowAnyException();
 }

 /**
  * What the screen shows is what transport decided, not a second opinion.
  *
  * <p>A screen saying "ready" while transport refuses the port would give an operator a reason to
  * distrust the screen, and there is no way back from that. So the view carries the refusal the
  * decision itself produces.
  */
 @Test void thescreenIsGivenTheSameAnswerTransportActsOn() throws Exception {
  try(var melsec=new FakeMelsec()){
   melsec.name(0,"STK-01");
   melsec.words[4]=9;                             // ERROR
   melsec.words[5]=0b11;
   wire(melsec.port());
   inOperation();
   assertThat(waitFor(()->"EQUIPMENT_IN_FAULT".equals(refusalFor(portId)))).isTrue();

   var shown=states.list(SITE).stream().filter(s->s.unitId()==unitId).findFirst().orElseThrow();
   assertThat(shown.wired()).isTrue();
   assertThat(shown.online()).isTrue();
   assertThat(shown.status()).isEqualTo("ERROR");
   assertThat(shown.fromDevice()).as("in OPERATION it is the machine's own state").isTrue();
   assertThat(shown.lastSeenAt()).isNotNull();
   assertThat(shown.refusal()).as("the same answer transport acts on").isEqualTo("EQUIPMENT_IN_FAULT");
  }
 }

 /** A unit nobody wired reads as unwired and refuses nothing, which is the honest pair. */
 @Test void anunwiredUnitReadsAsUnwiredAndRefusesNothing(){
  inOperation();
  var shown=states.list(SITE).stream().filter(s->s.unitId()==unitId).findFirst().orElseThrow();
  assertThat(shown.wired()).isFalse();
  assertThat(shown.refusal()).isNull();
  assertThat(shown.online()).as("nothing is wired, so there is nothing to be online").isNull();
 }

 /** In SIMULATION the screen shows what somebody set, and says so. */
 @Test void insimulationTheScreenShowsTheSimulatedState(){
  jdbc.update("INSERT INTO equipment.simulation_state(unit_id,enabled,call_enabled,status) VALUES(?,TRUE,FALSE,'BUSY')"
   +" ON CONFLICT(unit_id) DO UPDATE SET enabled=TRUE,call_enabled=FALSE,status='BUSY'",unitId);
  var shown=states.list(SITE).stream().filter(s->s.unitId()==unitId).findFirst().orElseThrow();
  assertThat(shown.fromDevice()).isFalse();
  assertThat(shown.status()).isEqualTo("BUSY");
  assertThat(shown.callEnabled()).isFalse();
  assertThat(shown.refusal()).isEqualTo("EQUIPMENT_CALL_DISABLED");
 }

 /** A vehicle's document describes a vehicle. Equipment may not be put in use on one. */
 @Test void equipmentMayOnlyBePutInUseOnAnEquipmentDocument(){
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
  var c=connections.create(SITE,new ConnectionSettings("TCP","CLIENT","127.0.0.1",2097,true));
  var vehicleDoc=protocols.create(SITE,new ProtocolSettings("PC","1.0","PC-FRAME-EXAMPLE"));
  assertThatThrownBy(()->links.create(SITE,
   new EquipmentCommunicationSettings(unitId,c.id(),vehicleDoc.id(),"STK-01",true)))
   .isInstanceOf(ConfigurationException.class).hasMessage("EQUIPMENT_ADAPTER_REQUIRED");

  // And a link that waits for the far side to dial in cannot be polled, so it is refused too.
  var waiting=connections.create(SITE,new ConnectionSettings("TCP","SERVER","127.0.0.1",2096,true));
  var equipmentDoc=protocols.create(SITE,new ProtocolSettings("PLC","1.0","EQUIPMENT-MC-EXAMPLE"));
  assertThatThrownBy(()->links.create(SITE,
   new EquipmentCommunicationSettings(unitId,waiting.id(),equipmentDoc.id(),"STK-01",true)))
   .isInstanceOf(ConfigurationException.class).hasMessage("EQUIPMENT_NEEDS_A_DIALLED_LINK");
 }
}
