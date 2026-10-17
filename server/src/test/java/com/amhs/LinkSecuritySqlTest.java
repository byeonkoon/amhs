package com.amhs;
import com.amhs.equipment.infrastructure.EquipmentLinks;
import com.amhs.integration.CommandOutbox;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.VehicleListening;
import com.amhs.integration.infrastructure.DeviceTls;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import javax.net.ssl.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Who is at the other end of a link, and whether anybody can listen in.
 *
 * <p>A dial-in link used to trust whatever arrived and said a vehicle's name. The name is not a
 * secret -- it is in the map file, on the screens and in every frame on the wire -- so anything
 * that could reach the port could drive the fleet. What is proved here is that the two questions
 * are now separate and both are asked: <b>who are you</b> is the certificate, and <b>what may you
 * speak for</b> is still the vehicles wired to that profile.
 *
 * <p>The material is made with {@code keytool} the way {@code docs/link-security.md} tells an
 * operator and a gateway to make theirs, so what is tested is the procedure as written rather than
 * something assembled only in a test.
 *
 * <p>This class declares properties of its own and therefore gets its own application context, at
 * the cost of another connection pool on the test cluster. It cannot share: the key material has
 * to exist before the context that reads it is built.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class LinkSecuritySqlTest {
 @Autowired @Qualifier("jsonLineVehicleLinks") DeviceChannel link;
 @Autowired VehicleListening listening;
 @Autowired CommandOutbox outbox; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 @Autowired EquipmentLinks equipment;
 private static final long SITE=1, AGV1=2, AGV2=3;
 private static final long PROFILE=9541, PROTOCOL=9541, LINKED=9541, LINKED2=9542;
 private static final String PASS="test-pass";
 private static final String GATEWAY="oht-gateway-1", CONTROLLER="amhs-controller";
 private static Path dir;

 // Made before the context is built, because the context reads these files as it starts.
 static {
  if("true".equals(System.getenv("AMHS_SQL_TEST"))) try{ material(); }
  catch(Exception e){ throw new IllegalStateException("could not make test TLS material",e); }
 }
 @DynamicPropertySource static void tlsMaterial(DynamicPropertyRegistry registry){
  registry.add("amhs.device.tls.keystore",()->dir.resolve("server.p12").toString());
  registry.add("amhs.device.tls.keystore-password",()->PASS);
  registry.add("amhs.device.tls.truststore",()->dir.resolve("trust.p12").toString());
  registry.add("amhs.device.tls.truststore-password",()->PASS);
 }

 /**
  * A CA, a certificate for this controller, two gateway certificates and one nobody signed.
  *
  * <p>Exactly the keytool run in the document. The stranger matters as much as the gateway: it is
  * signed by the same authority and carries another name, which is the only way to tell "we trust
  * this certificate" apart from "this is the machine the profile named".
  */
 private static void material() throws Exception {
  dir=Path.of("target","test-tls");
  Files.createDirectories(dir);
  // Kept between runs because keytool is slow, and stamped because material made for an older
  // version of this test is worse than none: it fails in a way that looks like the code.
  var stamp=dir.resolve("made-for.txt");
  if(Files.exists(stamp)&&Files.readString(stamp).equals(SHAPE)) return;
  try(var old=Files.list(dir)){ for(var file:old.toList()) Files.deleteIfExists(file); }
  ca();
  // Every name this certificate is used by, which is what the document tells an operator to do:
  // the test dials 127.0.0.1 and asks for the name, and the gateway dials in and checks the name
  // it was given.
  signed("server",CONTROLLER,"san=dns:"+CONTROLLER+",dns:localhost,ip:127.0.0.1");
  signed("gateway",GATEWAY,null);
  signed("stranger","other-gateway",null);
  // Signed by nobody this site trusts, which is a different failure from the wrong name.
  keytool("-genkeypair","-alias","rogue","-keyalg","RSA","-keysize","2048","-validity","2",
   "-dname","CN=rogue-controller","-ext","san=dns:"+CONTROLLER+",dns:localhost,ip:127.0.0.1",
   "-keystore",p12("rogue"),"-storetype","PKCS12","-storepass",PASS);
  Files.writeString(dir.resolve("made-for.txt"),SHAPE);
 }
 /** What the material above is made for. Changing the shape changes this. */
 private static final String SHAPE="ca+server("+CONTROLLER+")+gateway("+GATEWAY+")+stranger+rogue v1";
 private static void ca() throws Exception {
  keytool("-genkeypair","-alias","ca","-keyalg","RSA","-keysize","2048","-validity","2",
   "-dname","CN=AMHS Link CA","-ext","bc:c=ca:true",
   "-keystore",p12("ca"),"-storetype","PKCS12","-storepass",PASS);
  keytool("-exportcert","-alias","ca","-keystore",p12("ca"),"-storepass",PASS,
   "-file",dir.resolve("ca.crt").toString());
  keytool("-importcert","-alias","ca","-keystore",p12("trust"),"-storetype","PKCS12",
   "-storepass",PASS,"-file",dir.resolve("ca.crt").toString(),"-noprompt");
 }
 /** One key pair, signed by the CA, in a store that also trusts the CA. */
 private static void signed(String alias,String commonName,String extension) throws Exception {
  keytool("-genkeypair","-alias",alias,"-keyalg","RSA","-keysize","2048","-validity","2",
   "-dname","CN="+commonName,"-keystore",p12(alias),"-storetype","PKCS12","-storepass",PASS);
  keytool("-certreq","-alias",alias,"-keystore",p12(alias),"-storepass",PASS,
   "-file",dir.resolve(alias+".csr").toString());
  var sign=new java.util.ArrayList<String>(List.of("-gencert","-alias","ca","-keystore",p12("ca"),
   "-storepass",PASS,"-infile",dir.resolve(alias+".csr").toString(),
   "-outfile",dir.resolve(alias+".crt").toString(),"-validity","2","-rfc"));
  if(extension!=null){ sign.add("-ext"); sign.add(extension); }
  keytool(sign.toArray(String[]::new));
  keytool("-importcert","-alias","ca","-keystore",p12(alias),"-storepass",PASS,
   "-file",dir.resolve("ca.crt").toString(),"-noprompt");
  keytool("-importcert","-alias",alias,"-keystore",p12(alias),"-storepass",PASS,
   "-file",dir.resolve(alias+".crt").toString(),"-noprompt");
 }
 private static String p12(String name){ return dir.resolve(name+".p12").toString(); }
 private static void keytool(String... args) throws Exception {
  var command=new java.util.ArrayList<String>();
  command.add(Path.of(System.getProperty("java.home"),"bin","keytool").toString());
  command.addAll(List.of(args));
  var process=new ProcessBuilder(command).redirectErrorStream(true).start();
  var said=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  if(!process.waitFor(60,TimeUnit.SECONDS)||process.exitValue()!=0)
   throw new IllegalStateException("keytool "+String.join(" ",args)+" said: "+said);
 }

 /** A client or server context from one of those stores, which is what a gateway would have. */
 private static SSLContext contextOf(String alias) throws Exception {
  var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
  keys.init(load(p12(alias)),PASS.toCharArray());
  var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
  trust.init(load(p12("trust")));
  var context=SSLContext.getInstance("TLS");
  context.init(keys.getKeyManagers(),trust.getTrustManagers(),null);
  return context;
 }
 /** The same, trusting nobody in particular -- for the server nobody signed. */
 private static SSLContext looseContextOf(String alias) throws Exception {
  var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
  keys.init(load(p12(alias)),PASS.toCharArray());
  var context=SSLContext.getInstance("TLS");
  context.init(keys.getKeyManagers(),null,null);
  return context;
 }
 private static KeyStore load(String path) throws Exception {
  var store=KeyStore.getInstance("PKCS12");
  try(var in=Files.newInputStream(Path.of(path))){ store.load(in,PASS.toCharArray()); }
  return store;
 }

 @BeforeEach @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.vehicle_communication WHERE id IN (?,?)",LINKED,LINKED2);
  jdbc.update("DELETE FROM equipment.communication WHERE id=?",LINKED);
  jdbc.update("DELETE FROM integration.connection_profile WHERE id=?",PROFILE);
  jdbc.update("DELETE FROM integration.protocol_profile WHERE id=?",PROTOCOL);
  jdbc.update("UPDATE integration.command_outbox SET status='WITHDRAWN',settled_at=now(),"
   +"last_outcome='WITHDRAWN' WHERE command_id LIKE 'tls-test-%'");
  jdbc.update("DELETE FROM integration.command_outbox WHERE command_id LIKE 'tls-test-%'");
  listening.listen();     // configuration decides which ports are open, both ways
 }

 private static int freePort() throws IOException {
  try(var probe=new ServerSocket(0)){ return probe.getLocalPort(); }
 }

 /** A profile this controller listens on, secured, expecting that gateway's name. */
 private void listenFor(int port,boolean tls,String peerName){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,"
   +"tls,peer_name,version) VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'SERVER',?,?,0)",
   PROFILE,SITE,port,tls,peerName);
  wireVehicles();
  listening.listen();
 }
 /** A profile this controller dials out on, secured, expecting that controller's name. */
 private void dialOut(int port,boolean tls,String peerName){
  jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,"
   +"tls,peer_name,version) VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',?,?,0)",
   PROFILE,SITE,port,tls,peerName);
  wireVehicles();
 }
 private void wireVehicles(){
  jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
   +" VALUES(?,?,'PC','1.0','JSON_LINE_V1',0)",PROTOCOL,SITE);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','V-01',TRUE)",
   LINKED,SITE,AGV1,PROFILE,PROTOCOL);
  jdbc.update("INSERT INTO integration.vehicle_communication(id,site_id,vehicle_id,connection_profile_id,"
   +"protocol_profile_id,manufacturer,external_vehicle_id,enabled) VALUES(?,?,?,?,?,'TEST','V-02',TRUE)",
   LINKED2,SITE,AGV2,PROFILE,PROTOCOL);
 }
 private com.amhs.integration.DeviceCommand command(long vehicleId){
  String id="tls-test-"+System.nanoTime();
  return tx.execute(s->outbox.enqueueForVehicle(SITE,vehicleId,id,"MOVE","{\"authorizedNodeIds\":[203]}"));
 }
 private DeviceChannel.Verdict sendOnceConnected(long vehicleId) throws InterruptedException {
  DeviceChannel.Verdict verdict=null;
  for(int i=0;i<100;i++){
   verdict=link.send(command(vehicleId));
   if(!"VEHICLE_NOT_CONNECTED".equals(verdict.detail())) return verdict;
   Thread.sleep(20);
  }
  return verdict;
 }

 /** A gateway as the document tells one to be: its own certificate, and this site's CA to check ours. */
 private static final class Gateway implements AutoCloseable {
  final Socket socket; final BlockingQueue<String> heard=new LinkedBlockingQueue<>();
  private final BufferedWriter out; private final Thread thread;
  private final CountDownLatch gone=new CountDownLatch(1);
  Gateway(String alias,int port,boolean plain) throws Exception {
   if(plain) socket=new Socket("127.0.0.1",port);
   else{
    var ssl=(SSLSocket)contextOf(alias).getSocketFactory().createSocket("127.0.0.1",port);
    var parameters=ssl.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(parameters);
    ssl.startHandshake();                       // the gateway checks this controller too
    socket=ssl;
   }
   socket.setTcpNoDelay(true);
   out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
   thread=Thread.ofVirtual().start(()->{
    try(var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8))){
     String line;
     while((line=in.readLine())!=null){
      heard.add(line);
      if(line.contains("\"type\":\"command\"")){
       String id=line.replaceAll(".*\"commandId\":\"([^\"]+)\".*","$1");
       say("{\"type\":\"ack\",\"commandId\":\""+id+"\"}");
      }
     }
    }catch(IOException ignored){ /* the test closes it, or this controller does */ }
    finally{ gone.countDown(); }
   });
  }
  void say(String frame) throws IOException { out.write(frame); out.write("\n"); out.flush(); }
  void hello(String vehicle) throws IOException { say("{\"type\":\"hello\",\"vehicle\":\""+vehicle+"\"}"); }
  boolean wasLetGo() throws InterruptedException { return gone.await(3,TimeUnit.SECONDS); }
  public void close() throws IOException { socket.close(); thread.interrupt(); }
 }

 /** A line controller this one dials out to, which may or may not be one anybody signed. */
 private static final class FarSide implements AutoCloseable {
  final ServerSocket server; final BlockingQueue<String> heard=new LinkedBlockingQueue<>();
  private final Thread thread;
  FarSide(String alias,boolean plain,boolean trusted) throws Exception {
   server=plain?new ServerSocket(0)
    :(trusted?contextOf(alias):looseContextOf(alias)).getServerSocketFactory().createServerSocket(0);
   thread=Thread.ofVirtual().start(()->{
    while(!server.isClosed()){
     try(var s=server.accept();
         var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
         var out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8))){
      String line;
      while((line=in.readLine())!=null){
       heard.add(line);
       if(line.contains("\"type\":\"command\"")){
        String id=line.replaceAll(".*\"commandId\":\"([^\"]+)\".*","$1");
        out.write("{\"type\":\"ack\",\"commandId\":\""+id+"\"}\n"); out.flush();
       }
      }
     }catch(IOException ignored){ /* a failed handshake, or the test closing */ }
    }
   });
  }
  int port(){ return server.getLocalPort(); }
  public void close() throws IOException { server.close(); thread.interrupt(); }
 }

 /**
  * A gateway with no certificate cannot get in, however well it knows the vehicle's name.
  *
  * <p>The name was the whole of the old door, and it is written in the map file.
  */
 @Test void agatewayWithNoCertificateIsNotLetIn() throws Exception {
  int port=freePort();
  listenFor(port,true,GATEWAY);
  try(var gateway=new Gateway("gateway",port,true)){        // plaintext, as before
   gateway.hello("V-01");
   assertThat(gateway.wasLetGo()).as("the handshake it never made ends it").isTrue();
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   assertThat(verdict.detail()).isEqualTo("VEHICLE_NOT_CONNECTED");
  }
 }

 /**
  * A certificate this site's authority signed, for another machine, is not this machine.
  *
  * <p>The difference between "we trust this certificate" and "this is the one the profile named",
  * which is the distinction a trust store alone cannot make.
  */
 @Test void agatewayWithTheWrongNameIsClosed() throws Exception {
  int port=freePort();
  listenFor(port,true,GATEWAY);
  try(var stranger=new Gateway("stranger",port,false)){
   stranger.hello("V-01");
   assertThat(stranger.wasLetGo()).as("certified, and not by that name").isTrue();
   assertThat(link.send(command(AGV1)).detail()).isEqualTo("VEHICLE_NOT_CONNECTED");
  }
 }

 /**
  * The certified gateway is let in, and may still only speak for its own vehicles.
  *
  * <p>Both questions, in order. The certificate says who arrived; the vehicle it claims is what it
  * may speak for, and that answer is the same as it was before any of this -- which is the point.
  */
 @Test void thecertifiedGatewayIsLetInAndStillOnlyClaimsItsOwn() throws Exception {
  int port=freePort();
  listenFor(port,true,GATEWAY);
  try(var gateway=new Gateway("gateway",port,false)){
   gateway.hello("V-01");
   var verdict=sendOnceConnected(AGV1);
   assertThat(verdict.outcome()).as("it got through over TLS").isEqualTo(DeviceChannel.Outcome.DELIVERED);
   assertThat(gateway.heard.poll(2,TimeUnit.SECONDS)).contains("\"type\":\"command\"");

   // A vehicle this profile does not use buys nothing, certificate or no certificate.
   gateway.say("{\"type\":\"hello\",\"vehicle\":\"V-99\"}");
   assertThat(gateway.wasLetGo()).as("a name this profile does not use ends the connection").isTrue();
  }
 }

 /** Dialling out: the far side's certificate has to be one we trust, for the name we expect. */
 @Test void adialledControllerHasToProveWhoItIs() throws Exception {
  try(var farSide=new FarSide("server",false,true)){
   dialOut(farSide.port(),true,CONTROLLER);
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).as("certified as %s",CONTROLLER).isEqualTo(DeviceChannel.Outcome.DELIVERED);
   assertThat(farSide.heard.poll(2,TimeUnit.SECONDS)).contains("\"type\":\"command\"");
  }
 }

 /**
  * A certificate nobody we trust signed means nothing was written -- and nothing was written in
  * plaintext instead.
  *
  * <p>The second half is the one that matters. A quiet downgrade would leave every verdict above
  * this looking exactly the same as a secured link.
  */
 @Test void anuntrustedFarSideGetsNothingAndNoPlaintextEither() throws Exception {
  try(var rogue=new FarSide("rogue",false,false)){
   dialOut(rogue.port(),true,CONTROLLER);
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).as("nothing was written").isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   assertThat(rogue.heard.poll(500,TimeUnit.MILLISECONDS)).as("and nothing arrived in the clear").isNull();
  }
 }

 /**
  * A plaintext far side on a secured profile gets no command: the link is not downgraded to reach it.
  *
  * <p>What it does get is the attempt to shake hands, which is where this one stops. That is also
  * the shape of the first defect this test found: a socket that opens and then says nothing left
  * the handshake waiting with no deadline of its own, and the thread that was publishing commands
  * waited with it. The handshake now gets the same patience as the connection and no more.
  */
 @Test void aplaintextFarSideOnASecuredProfileGetsNoCommand() throws Exception {
  try(var plain=new FarSide("server",true,true)){
   dialOut(plain.port(),true,CONTROLLER);
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   var arrived=new java.util.ArrayList<String>();
   String line;
   while((line=plain.heard.poll(300,TimeUnit.MILLISECONDS))!=null) arrived.add(line);
   assertThat(arrived).as("the handshake, and not one command")
    .noneMatch(said->said.contains("command"));
  }
 }

 /**
  * Without key material, a link that asks for TLS says so and does not open.
  *
  * <p>Three different answers, because they send somebody to three different places: nothing is
  * configured, a file named in the configuration is not there, and a trust store was left out --
  * the last one mattering because without it the only authorities left are the public web's, which
  * have never heard of this plant.
  */
 @Test void withoutMaterialASecuredLinkRefusesRatherThanFallsBack(){
  assertThat(new DeviceTls("","","","","").problem()).isEqualTo("TLS_NOT_CONFIGURED");
  assertThat(new DeviceTls(dir.resolve("server.p12").toString(),PASS,"","","").problem())
   .isEqualTo("TLS_TRUSTSTORE_REQUIRED");
  assertThat(new DeviceTls(dir.resolve("nothing-here.p12").toString(),PASS,"",
   dir.resolve("trust.p12").toString(),PASS).problem()).isEqualTo("TLS_KEYSTORE_MISSING");
  assertThat(new DeviceTls(dir.resolve("server.p12").toString(),"wrong-password","",
   dir.resolve("trust.p12").toString(),PASS).problem()).isEqualTo("TLS_MATERIAL_UNREADABLE");
 }

 /**
  * A word block cannot be secured, so a profile that says it is does not get connected to.
  *
  * <p>Modbus and MELSEC have no authentication and no TLS: the protocols predate the question.
  * Connecting in plaintext anyway would leave a setting that reads as protection, which is worse
  * than not having the setting. Where such a machine must be protected, something stands in front
  * of it -- which is docs/link-security.md's business, not this adapter's.
  */
 @Test void awordBlockProfileThatAsksForTlsIsNotConnectedTo() throws Exception {
  long unitId=jdbc.queryForObject("SELECT id FROM equipment.unit WHERE site_id=? ORDER BY id LIMIT 1",
   Long.class,SITE);
  try(var listener=new ServerSocket(0)){
   var arrived=new java.util.concurrent.atomic.AtomicInteger();
   var thread=Thread.ofVirtual().start(()->{
    while(!listener.isClosed()){
     try{ listener.accept(); arrived.incrementAndGet(); }
     catch(IOException e){ return; }
    }
   });
   jdbc.update("INSERT INTO integration.connection_profile(id,site_id,comm_type,host,port,enabled,role,"
    +"tls,peer_name,version) VALUES(?,?,'TCP','127.0.0.1',?,TRUE,'CLIENT',TRUE,NULL,0)",
    PROFILE,SITE,listener.getLocalPort());
   jdbc.update("INSERT INTO integration.protocol_profile(id,site_id,proto_type,proto_version,adapter_key,version)"
    +" VALUES(?,?,'PLC','1.0','EQUIPMENT-MC-EXAMPLE',0)",PROTOCOL,SITE);
   jdbc.update("INSERT INTO equipment.communication(id,site_id,unit_id,connection_profile_id,"
    +"protocol_profile_id,external_unit_id,enabled) VALUES(?,?,?,?,?,'STK-01',TRUE)",
    LINKED,SITE,unitId,PROFILE,PROTOCOL);

   for(int i=0;i<5;i++){ equipment.poll(); Thread.sleep(20); }
   assertThat(arrived.get()).as("nothing was connected to in the clear").isZero();
   thread.interrupt();
  }finally{
   jdbc.update("DELETE FROM equipment.communication WHERE id=?",LINKED);
   jdbc.update("DELETE FROM equipment.device_state WHERE unit_id IN"
    +" (SELECT id FROM equipment.unit WHERE site_id=?)",SITE);
  }
 }

 /**
  * A profile that asks for a name to check without TLS is refused when it is saved.
  *
  * <p>A name checked on a plaintext link is nothing checked at all, and a setting that does nothing
  * is worse than one that is missing: it reads as protection.
  */
 @Test void anameToCheckWithoutTlsIsRefused(){
  assertThatThrownBy(()->new com.amhs.integration.ConnectionSettings("TCP","SERVER","127.0.0.1",
   5000,true,false,GATEWAY).validate())
   .hasMessageContaining("PEER_NAME_NEEDS_TLS");
 }
}
