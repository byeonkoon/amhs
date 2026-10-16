package com.amhs;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.CommandStatus;
import com.amhs.integration.DeviceGateway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * What the link does when the line misbehaves.
 *
 * <p>This system's whole claim about sending is three-valued: nothing was written (send it again),
 * it was acknowledged (never execution), or it was written and nobody can say (never repeated by
 * this system on its own). Everything above the link is built on that, and every test of it so far
 * has been of one verdict at a time on a line that was otherwise well behaved.
 *
 * <p>So this is the line behaving badly: silent, refusing, dying mid-write, answering late,
 * answering twice, and doing it while more work arrives. The question each time is not only
 * whether the verdict is right but whether the far side was ever told the same thing twice --
 * because a command delivered twice is a vehicle that moves twice.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class FaultInjectionSqlTest {
 @Autowired DeviceGateway gateway; @Autowired CommandOutbox outbox;
 @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1;
 /**
  * Vehicles and profiles of this test's own.
  *
  * <p>None of the state this exercises is this test's alone: an unsettled command holds up
  * everything behind it for that vehicle, an open link is cached against its connection profile
  * for the life of the application, and a vehicle's link is whichever enabled communication row
  * the database hands back first. So this takes two vehicles <b>nobody else has wired</b> and
  * gives them a connection profile of their own, fresh for every test. Sharing any of the three
  * with the rest of the suite is how this test first broke four of its neighbours and then itself.
  */
 private long vehicleA, vehicleB, PROFILE, PROTOCOL, LINKED1, LINKED2;
 /** The site's run mode as this test found it, to be handed back untouched. */
 private String runMode;
 private static long nextProfile=9700;

 /**
  * A controller that can be made to misbehave, and that remembers everything it was ever told.
  *
  * <p>The record of what it heard is the point. A verdict can be argued about; a second copy of
  * the same command id arriving on the wire cannot.
  */
 private static final class Flaky implements AutoCloseable {
  final ServerSocket server;
  /** Every command id this controller has been sent, in order, repeats included. */
  final List<String> heard=Collections.synchronizedList(new ArrayList<>());
  /** null to answer normally; set to swallow the acknowledgement, or to die mid-write. */
  volatile boolean silent=false, dieOnWrite=false, ackTwice=false;
  private final List<Socket> open=Collections.synchronizedList(new ArrayList<>());
  private final Thread thread;

  Flaky() throws IOException {
   server=new ServerSocket(0);
   thread=Thread.ofVirtual().start(()->{
    while(!server.isClosed()){
     try(Socket s=server.accept()){
      open.add(s);
      var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
      var out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8));
      String line;
      while((line=in.readLine())!=null){
       if(!line.contains("\"type\":\"command\"")) continue;
       String id=line.replaceAll(".*\"commandId\":\"([^\"]+)\".*","$1");
       heard.add(id);
       if(dieOnWrite){ s.close(); break; }      // the line dies with the bytes on it
       if(silent) continue;                      // written, and never answered
       out.write("{\"type\":\"ack\",\"commandId\":\""+id+"\"}\n"); out.flush();
       // A controller that answers twice is a controller whose answer was lost and resent.
       if(ackTwice){ out.write("{\"type\":\"ack\",\"commandId\":\""+id+"\"}\n"); out.flush(); }
      }
     }catch(IOException ignored){ /* the next connection, or the test closing */ }
    }
   });
  }
  int port(){ return server.getLocalPort(); }
  /** How many times this controller was told about that command. */
  long times(String id){ synchronized(heard){ return heard.stream().filter(id::equals).count(); } }
  public void close() throws IOException {
   server.close(); thread.interrupt();
   synchronized(open){ for(var s:open) try{ s.close(); }catch(IOException ignored){ } }
  }
 }

 @BeforeEach void ownVehiclesAndProfiles(){
  var free=jdbc.queryForList("SELECT v.id FROM fleet.vehicle_profile v"
   +" JOIN topology.map_revision m ON m.id=v.revision_id AND m.status='ACTIVE'"
   +" WHERE v.site_id=? AND v.enabled"
   +" AND NOT EXISTS (SELECT 1 FROM integration.vehicle_communication c WHERE c.vehicle_id=v.id)"
   +" ORDER BY v.id DESC LIMIT 2",Long.class,SITE);
  assertThat(free).as("this test needs two vehicles nobody else has wired").hasSize(2);
  vehicleA=free.get(0); vehicleB=free.get(1);
  PROFILE=++nextProfile; PROTOCOL=PROFILE; LINKED1=PROFILE; LINKED2=PROFILE+10000;
  // A site in SIMULATION has every command carried out by a simulated vehicle and nothing reaches
  // a link at all. This test is about the link, so it runs the site in OPERATION and hands it back
  // the way it found it -- a neighbour that wants its vehicles simulated must still find them so.
  runMode=jdbc.queryForObject("SELECT run_mode FROM platform.site_operation WHERE site_id=?",
   String.class,SITE);
  jdbc.update("UPDATE platform.site_operation SET run_mode='OPERATION' WHERE site_id=?",SITE);
  clean();
 }

 @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id IN (?,?)",LINKED1,LINKED2);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  // Everything this test wrote. Not narrowed to a site: a command is held while an older one for
  // the same vehicle_id is unsettled, whatever site that one belongs to, and this test leaves
  // commands in doubt on purpose.
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN',settled_at=now(),"
   +"last_outcome='WITHDRAWN' WHERE vehicle_id IN (?,?)"
   +" AND status IN ('PENDING','DISPATCHING','FAILED')",vehicleA,vehicleB);
  jdbc.update("DELETE FROM integration.command_outbox WHERE command_id LIKE 'fault-%'");
  // The vehicles' reported state is deliberately *not* cleared: one that has never reported cannot
  // be assigned, and taking that away is how this test first broke three of its neighbours.
  //
  // Nothing leaves a site that is stopped or still owes a restart check, which is the whole point
  // of that gate -- and another test may well have left it in either state. This one is about what
  // the wire does, so it starts from a site that is willing to send.
  jdbc.update("UPDATE platform.site_operation SET sequence_running=TRUE,restart_check_since=NULL"
   +" WHERE site_id=?",SITE);
 }

 @AfterEach void handTheSiteBack(){
  if(runMode!=null) jdbc.update("UPDATE platform.site_operation SET run_mode=? WHERE site_id=?",runMode,SITE);
 }

 private void wire(int port){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,version)"
   +" VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',0)",PROFILE,SITE,port);
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','JSON_LINE_V1',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','F-01',TRUE)",
   LINKED1,SITE,vehicleA,PROFILE,PROTOCOL);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','F-02',TRUE)",
   LINKED2,SITE,vehicleB,PROFILE,PROTOCOL);
 }
 private String command(long vehicleId){
  String id="fault-"+vehicleId+"-"+System.nanoTime();
  tx.executeWithoutResult(s->outbox.enqueueForVehicle(SITE,vehicleId,id,"MOVE","{\"authorizedNodeIds\":[203]}"));
  return id;
 }
 private CommandStatus statusOf(String id){
  return CommandStatus.valueOf(jdbc.queryForObject(
   "SELECT status FROM integration.command_outbox WHERE command_id=?",String.class,id));
 }
 private String outcomeOf(String id){
  return jdbc.queryForObject("SELECT last_outcome FROM integration.command_outbox WHERE command_id=?",
   String.class,id);
 }
 /**
  * Everything unsettled on these vehicles, whatever site it belongs to.
  *
  * <p>Carried in the assertion messages because "nothing was claimed" is the one failure this test
  * cannot explain by itself: a command is held while an older one for the same vehicle is open,
  * and when that older one is somebody else's the only way to see it is to print it.
  */
 private String outstanding(){
  return jdbc.query("SELECT command_id||' v'||vehicle_id||' site'||site_id||' '||status"
   +"||' '||COALESCE(last_outcome,'-')||'/'||COALESCE(last_error,'-') FROM integration.command_outbox"
   +" WHERE vehicle_id IN (?,?) AND status IN ('PENDING','DISPATCHING','FAILED') ORDER BY id",
   (r,n)->r.getString(1),vehicleA,vehicleB).toString();
 }

 /**
  * Written and never answered: in doubt, and this system never sends it again by itself.
  *
  * <p>The sweeps afterwards are the test. A command in doubt that quietly came round again would
  * be a vehicle told twice to move, and nothing above the link could tell.
  */
 @Test void acommandNobodyAnsweredIsNeverRepeatedByItself() throws Exception {
  try(var far=new Flaky()){
   far.silent=true;
   wire(far.port());
   String id=command(vehicleA);
   var first=gateway.publishPending(SITE);
   assertThat(first.inDoubt()).as("one in doubt; outstanding was %s",outstanding()).isEqualTo(1);
   assertThat(statusOf(id)).as("not queued again, not failed -- held").isEqualTo(CommandStatus.DISPATCHING);
   assertThat(outcomeOf(id)).isEqualTo("IN_DOUBT");

   for(int i=0;i<5;i++) gateway.publishPending(SITE);
   assertThat(far.times(id)).as("the controller heard it once and only once").isEqualTo(1);
  }
 }

 /** A late acknowledgement resolves the doubt: receipt is receipt whenever it turns up. */
 @Test void alateAcknowledgementSettlesACommandThatWasInDoubt() throws Exception {
  try(var far=new Flaky()){
   far.silent=true;
   wire(far.port());
   String id=command(vehicleA);
   gateway.publishPending(SITE);
   assertThat(outcomeOf(id)).isEqualTo("IN_DOUBT");

   outbox.acknowledge(id);
   assertThat(statusOf(id)).isEqualTo(CommandStatus.ACCEPTED);
   for(int i=0;i<3;i++) gateway.publishPending(SITE);
   assertThat(far.times(id)).as("settled means settled").isEqualTo(1);
  }
 }

 /**
  * Nothing listening: nothing was written, so it may be sent again -- and when it is, the
  * controller hears it for the first time.
  */
 @Test void whatWasNeverWrittenIsSentAgainAndArrivesOnce() throws Exception {
  int dead;
  try(var closed=new ServerSocket(0)){ dead=closed.getLocalPort(); }
  wire(dead);
  String id=command(vehicleA);
  var refused=gateway.publishPending(SITE);
  assertThat(refused.notDelivered()).as("nothing written; outstanding was %s",outstanding()).isEqualTo(1);
  assertThat(statusOf(id)).as("queued for another go").isEqualTo(CommandStatus.FAILED);

  // The same port, now with something behind it. A real line coming back up.
  try(var far=new Flaky()){
   jdbc.update("UPDATE integration.command_outbox SET available_at=now() WHERE command_id=?",id);
   jdbc.update("UPDATE integration.connection_profile SET port=? WHERE id=?",far.port(),PROFILE);
   var sent=gateway.publishPending(SITE);
   assertThat(sent.delivered()).isEqualTo(1);
   assertThat(far.times(id)).as("heard once, on the attempt that worked").isEqualTo(1);
  }
 }

 /** The line dies with the bytes on it: nobody can say, so nobody says. */
 @Test void alineThatDiesMidWriteLeavesTheCommandInDoubt() throws Exception {
  try(var far=new Flaky()){
   far.dieOnWrite=true;
   wire(far.port());
   String id=command(vehicleA);
   gateway.publishPending(SITE);
   assertThat(outcomeOf(id)).as("written, and the answer went with the line").isEqualTo("IN_DOUBT");
   for(int i=0;i<3;i++) gateway.publishPending(SITE);
   assertThat(far.times(id)).isEqualTo(1);
  }
 }

 /**
  * An answer that arrives twice is still one delivery.
  *
  * <p>The link's acknowledgement settles the send and nothing more: the command is SENT, which is
  * this controller saying the far side took the bytes. ACCEPTED is a further thing the vehicle
  * says for itself, and a repeated line acknowledgement must not be mistaken for it.
  */
 @Test void acontrollerThatAnswersTwiceIsStillOneDelivery() throws Exception {
  try(var far=new Flaky()){
   far.ackTwice=true;
   wire(far.port());
   String id=command(vehicleA);
   var sent=gateway.publishPending(SITE);
   assertThat(sent.delivered()).isEqualTo(1);
   Thread.sleep(200);                       // let the second acknowledgement land
   assertThat(statusOf(id)).as("receipt of the bytes, not the vehicle's own acceptance")
    .isEqualTo(CommandStatus.SENT);
   assertThat(far.times(id)).isEqualTo(1);

   // And the stray second answer did not leave anything behind that a later sweep would act on.
   for(int i=0;i<3;i++) gateway.publishPending(SITE);
   assertThat(far.times(id)).isEqualTo(1);
  }
 }

 /**
  * A vehicle's commands leave in order, and a command in doubt holds the ones behind it.
  *
  * <p>Nobody knows whether the first one is being acted on. Sending the next would be deciding
  * that it is not.
  */
 @Test void acommandInDoubtHoldsTheRestOfThatVehiclesQueue() throws Exception {
  try(var far=new Flaky()){
   far.silent=true;
   wire(far.port());
   String first=command(vehicleA), second=command(vehicleA);
   gateway.publishPending(SITE);
   assertThat(outcomeOf(first)).isEqualTo("IN_DOUBT");

   for(int i=0;i<4;i++) gateway.publishPending(SITE);
   assertThat(far.times(second)).as("the one behind waited").isZero();
   assertThat(statusOf(second)).isEqualTo(CommandStatus.PENDING);

   // Once the first is resolved the queue moves again.
   far.silent=false;
   outbox.acknowledge(first);
   gateway.publishPending(SITE);
   assertThat(far.times(second)).as("and then went, once").isEqualTo(1);
  }
 }

 /**
  * Under load on a line that keeps failing, nothing is ever sent twice.
  *
  * <p>The counts are not the point -- what gets through on a line like this is whatever gets
  * through. The point is the last assertion: every command the controller heard, it heard once.
  */
 @Test void underLoadOnAFailingLineNothingArrivesTwice() throws Exception {
  try(var far=new Flaky()){
   wire(far.port());
   var ids=new ArrayList<String>();
   for(int round=0;round<6;round++){
    ids.add(command(vehicleA));
    ids.add(command(vehicleB));
    // The line misbehaves differently each round, which is what a line actually does.
    far.silent=round%3==1;
    far.dieOnWrite=round%3==2;
    gateway.publishPending(SITE);
    // Whatever is in doubt, somebody eventually settles -- here, the device's late answer.
    if(round%3==1) for(String id:new ArrayList<>(ids))
      if("IN_DOUBT".equals(outcomeOf(id))) outbox.acknowledge(id);
   }
   far.silent=false; far.dieOnWrite=false;
   for(int i=0;i<4;i++) gateway.publishPending(SITE);

   var twice=ids.stream().filter(id->far.times(id)>1).toList();
   assertThat(twice).as("no command reached the controller more than once").isEmpty();
   assertThat(far.heard).as("something did get through").isNotEmpty();
  }
 }
}
