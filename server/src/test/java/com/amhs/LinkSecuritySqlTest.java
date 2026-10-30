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
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
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
 * <p>A certificate can also be <b>withdrawn</b>: a revocation list is read with the trust store,
 * and a gateway on it does not get in however good its certificate is, while the gateways that are
 * not on it are unaffected. That list has a date of its own, and what happens when it runs out is
 * part of the answer rather than an oversight -- see the last tests here.
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
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 // The test that owns a port is the only thing that opens it. Several contexts live at once in a
 // test run, each with a five-second sweep that opens whatever the rows ask for, and a port can
 // only be held by one of them -- so the one that happens to win answers this test's gateway with
 // its own key material, and the handshake fails in a way that reads exactly like the defect.
 "amhs.device.tcp.auto-listen=false"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class LinkSecuritySqlTest {
 @Autowired @Qualifier("jsonLineVehicleLinks") DeviceChannel link;
 @Autowired VehicleListening listening;
 @Autowired CommandOutbox outbox; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 @Autowired EquipmentLinks equipment;
 // For the communication check report, which is where an operator meets any of this.
 @Autowired com.amhs.platform.OperationOptionConfiguration siteOptions;
 @Autowired com.amhs.fleet.VehicleConfiguration fleet;
 @Autowired com.amhs.integration.CommunicationConfiguration wiring;
 @Autowired com.amhs.integration.ConnectionConfiguration connections;
 @Autowired com.amhs.integration.ProtocolConfiguration protocols;
 @Autowired com.amhs.integration.VdaProfileConfiguration vdaProfiles;
 private static final long SITE=1, AGV1=2, AGV2=3;
 private static final long PROFILE=9541, PROTOCOL=9541, LINKED=9541, LINKED2=9542;
 private static final String PASS="test-pass";
 private static final String GATEWAY="oht-gateway-1", CONTROLLER="amhs-controller",
                            RETIRED="oht-gateway-gone";
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
  // The revocation list, in force for every test below. A directory, because a site with two
  // authorities has two lists and the configuration should not grow a line for each.
  //
  // In force for every test is deliberate and not convenience: what the list must not do is
  // change the answer for a gateway that is not on it, and the way to show that is to leave it
  // switched on while the rest of this class asks its old questions.
  registry.add("amhs.device.tls.crls",()->dir.resolve("revoked").toString());
 }

 /**
  * A CA, a certificate for this controller, two gateway certificates, one nobody signed, one that
  * has been withdrawn, and the list that withdrew it.
  *
  * <p>Exactly the keytool run in the document. The stranger matters as much as the gateway: it is
  * signed by the same authority and carries another name, which is the only way to tell "we trust
  * this certificate" apart from "this is the machine the profile named". The retired one is a
  * third case again: trusted authority, its own name, and on the list.
  */
 private static void material() throws Exception {
  dir=Path.of("target","test-tls");
  Files.createDirectories(dir);
  // Kept between runs because keytool is slow, and stamped because material made for an older
  // version of this test is worse than none: it fails in a way that looks like the code.
  //
  // Remade when it is getting old, too. The first run of this made two-day certificates and the
  // suite failed three days later with a handshake error -- a test that passes on Friday and fails
  // on Monday for a reason that has nothing to do with the code is worse than no test.
  var stamp=dir.resolve("made-for.txt");
  if(Files.exists(stamp)&&Files.readString(stamp).equals(SHAPE)
   &&Files.getLastModifiedTime(stamp).toInstant().isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(30))))
   return;
  try(var old=Files.walk(dir)){
   for(var file:old.sorted(java.util.Comparator.reverseOrder()).toList())
    if(!file.equals(dir)) Files.deleteIfExists(file);
  }
  Files.createDirectories(dir.resolve("revoked"));
  ca();
  // Every name this certificate is used by, which is what the document tells an operator to do:
  // the test dials 127.0.0.1 and asks for the name, and the gateway dials in and checks the name
  // it was given.
  signed("server",CONTROLLER,"san=dns:"+CONTROLLER+",dns:localhost,ip:127.0.0.1");
  signed("gateway",GATEWAY,null);
  signed("stranger","other-gateway",null);
  // Signed by nobody this site trusts, which is a different failure from the wrong name.
  keytool("-genkeypair","-alias","rogue","-keyalg","RSA","-keysize","2048","-validity","90",
   "-dname","CN=rogue-controller","-ext","san=dns:"+CONTROLLER+",dns:localhost,ip:127.0.0.1",
   "-keystore",p12("rogue"),"-storetype","PKCS12","-storepass",PASS);
  // A gateway that was on this floor and is not any more. Its certificate is as good as the
  // working one; the only thing wrong with it is the list.
  signed("retired",RETIRED,"san=dns:"+CONTROLLER+",dns:localhost,ip:127.0.0.1");
  revoke("retired");
  Files.writeString(dir.resolve("made-for.txt"),SHAPE);
 }
 /** What the material above is made for. Changing the shape changes this. */
 private static final String SHAPE="ca+server("+CONTROLLER+")+gateway("+GATEWAY+")+stranger+rogue"
  +"+retired("+RETIRED+")+crl v3, 90 days";
 /**
  * The list, naming that certificate's serial number.
  *
  * <p>The operator's procedure and not a convenience of the test: a revocation list is made by the
  * authority that issued the certificate, which here is the same {@code keytool} and the same CA
  * store. The serial is read from the certificate rather than typed, because a number typed into a
  * list by hand is a revocation of something else.
  */
 private static void revoke(String alias) throws Exception {
  var certificate=(X509Certificate)load(p12(alias)).getCertificate(alias);
  keytool("-gencrl","-alias","ca","-keystore",p12("ca"),"-storepass",PASS,
   "-id",certificate.getSerialNumber()+":4",          // 4: superseded
   "-validity","90","-rfc","-file",dir.resolve("revoked").resolve("ca.crl").toString());
 }
 private static void ca() throws Exception {
  keytool("-genkeypair","-alias","ca","-keyalg","RSA","-keysize","2048","-validity","90",
   "-dname","CN=AMHS Link CA","-ext","bc:c=ca:true",
   "-keystore",p12("ca"),"-storetype","PKCS12","-storepass",PASS);
  keytool("-exportcert","-alias","ca","-keystore",p12("ca"),"-storepass",PASS,
   "-file",dir.resolve("ca.crt").toString());
  keytool("-importcert","-alias","ca","-keystore",p12("trust"),"-storetype","PKCS12",
   "-storepass",PASS,"-file",dir.resolve("ca.crt").toString(),"-noprompt");
 }
 /** One key pair, signed by the CA, in a store that also trusts the CA. */
 private static void signed(String alias,String commonName,String extension) throws Exception {
  keytool("-genkeypair","-alias",alias,"-keyalg","RSA","-keysize","2048","-validity","90",
   "-dname","CN="+commonName,"-keystore",p12(alias),"-storetype","PKCS12","-storepass",PASS);
  keytool("-certreq","-alias",alias,"-keystore",p12(alias),"-storepass",PASS,
   "-file",dir.resolve(alias+".csr").toString());
  var sign=new java.util.ArrayList<String>(List.of("-gencert","-alias","ca","-keystore",p12("ca"),
   "-storepass",PASS,"-infile",dir.resolve(alias+".csr").toString(),
   "-outfile",dir.resolve(alias+".crt").toString(),"-validity","90","-rfc"));
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
    socket=ssl;
    // The gateway checks this controller too -- and a gateway whose own certificate is refused
    // hears about it here, as a fatal alert that closes the socket with nothing left to read or
    // write. That is an answer rather than an accident, so this ends here and it counts as let go.
    try{ ssl.startHandshake(); }
    catch(IOException refused){
     out=null; thread=null; gone.countDown();
     return;
    }
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
  void hello(String vehicle) throws IOException {
   if(out==null) return;                      // refused at the handshake; nothing to say it to
   say("{\"type\":\"hello\",\"vehicle\":\""+vehicle+"\"}");
  }
  boolean wasLetGo() throws InterruptedException { return gone.await(3,TimeUnit.SECONDS); }
  public void close() throws IOException {
   socket.close();
   if(thread!=null) thread.interrupt();
  }
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
  assertThat(new DeviceTls("","","","","","").problem()).isEqualTo("TLS_NOT_CONFIGURED");
  assertThat(new DeviceTls(dir.resolve("server.p12").toString(),PASS,"","","","").problem())
   .isEqualTo("TLS_TRUSTSTORE_REQUIRED");
  assertThat(new DeviceTls(dir.resolve("nothing-here.p12").toString(),PASS,"",
   dir.resolve("trust.p12").toString(),PASS,"").problem()).isEqualTo("TLS_KEYSTORE_MISSING");
  assertThat(new DeviceTls(dir.resolve("server.p12").toString(),"wrong-password","",
   dir.resolve("trust.p12").toString(),PASS,"").problem()).isEqualTo("TLS_MATERIAL_UNREADABLE");
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

 /**
  * The gateway that was taken off the floor cannot get back in, and its name is never reached.
  *
  * <p>Until this, a link read a signature and an expiry date. So a gateway that was removed,
  * replaced or lost kept a working certificate until it expired -- over two years with the
  * material the document tells an operator to make. The only way to stop it was to change the
  * authority, which means issuing new material to every other gateway on the floor as well: a
  * procedure nobody carries out for one retired box, which means in practice it was not stopped.
  *
  * <p>This certificate is signed by this site's authority and carries the name a profile would
  * expect of it. The only thing wrong with it is that it is on the list, and that is enough.
  */
 @Test void aretiredGatewayCertificateIsNotLetIn() throws Exception {
  int port=freePort();
  listenFor(port,true,RETIRED);               // the profile would even accept the name
  try(var gateway=new Gateway("retired",port,false)){
   gateway.hello("V-01");
   assertThat(gateway.wasLetGo()).as("withdrawn, whatever name it carries").isTrue();
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   assertThat(verdict.detail()).isEqualTo("VEHICLE_NOT_CONNECTED");
  }
 }

 /**
  * Dialling out to a controller whose certificate was withdrawn: nothing written, and nothing
  * written in the clear instead.
  *
  * <p>The second half is the one that matters, as everywhere else here. A link that quietly fell
  * back to plaintext would leave every verdict above it reading exactly like a secured one.
  */
 @Test void aretiredControllerGetsNothingAndNoPlaintextEither() throws Exception {
  try(var retired=new FarSide("retired",false,true)){
   dialOut(retired.port(),true,CONTROLLER);
   var verdict=link.send(command(AGV1));
   assertThat(verdict.outcome()).as("nothing was written").isEqualTo(DeviceChannel.Outcome.NOT_DELIVERED);
   assertThat(retired.heard.poll(500,TimeUnit.MILLISECONDS))
    .as("and nothing arrived in the clear").isNull();
  }
 }

 /**
  * A list that has run out refuses, and says so a week before it does.
  *
  * <p>This is what checking revocation from a file costs, stated plainly. A list has a date on it,
  * and after that date it no longer says what has been withdrawn since -- so using it would be
  * reading protection into something that has stopped protecting. The link does not open.
  *
  * <p>Which makes refreshing the list an operational duty, and an operator who forgets stops the
  * floor. So the state is announced while it is still only coming: {@code TLS_CRL_EXPIRES_SOON} on
  * the communication check, seven days out, beside the vehicles it will stop.
  *
  * <p>The date is given to this rather than taken from the clock, because this state arrives by
  * waiting and a test that waits a month for it is a test nobody runs.
  */
 @Test void alistThatHasRunOutRefusesRatherThanPasses() throws Exception {
  X509CRL list;
  try(var in=Files.newInputStream(dir.resolve("revoked").resolve("ca.crl"))){
   list=(X509CRL)java.security.cert.CertificateFactory.getInstance("X.509").generateCRL(in);
  }
  assertThat(list.getNextUpdate()).as("the list keytool made has a date on it").isNotNull();
  var runsOut=list.getNextUpdate().toInstant();

  assertThat(asOf(runsOut.minus(java.time.Duration.ofDays(30))).problem()).as("in date").isNull();
  assertThat(asOf(runsOut.minus(java.time.Duration.ofDays(30))).warning()).as("nowhere near").isNull();
  assertThat(asOf(runsOut.minus(java.time.Duration.ofDays(3))).warning()).isEqualTo("TLS_CRL_EXPIRES_SOON");
  assertThat(asOf(runsOut.minus(java.time.Duration.ofDays(3))).problem())
   .as("a warning is not a refusal").isNull();
  assertThat(asOf(runsOut.plusSeconds(1)).problem()).isEqualTo("TLS_CRL_STALE");
  assertThat(asOf(runsOut.plus(java.time.Duration.ofDays(400))).available()).isFalse();
 }

 /**
  * A list that was asked for and is not there is not the same as one that was never asked for.
  *
  * <p>Three answers again, because they send somebody to three different places: nothing is being
  * checked (a site that has not asked), the place named holds no list, and something is there that
  * is not a list.
  */
 @Test void alistAskedForAndNotThereIsSaidAsSuch() throws Exception {
  assertThat(withLists(dir.resolve("revoked").toString()).problem()).as("as configured").isNull();
  assertThat(withLists("").problem()).as("not checked at all, which is the older behaviour").isNull();
  assertThat(withLists(dir.resolve("nothing-here").toString()).problem()).isEqualTo("TLS_CRL_MISSING");
  var empty=Files.createDirectories(dir.resolve("no-lists-here"));
  assertThat(withLists(empty.toString()).problem()).as("a place with no list in it")
   .isEqualTo("TLS_CRL_MISSING");
  assertThat(withLists(dir.resolve("ca.crt").toString()).problem())
   .as("a certificate is not a list").isEqualTo("TLS_CRL_UNREADABLE");
 }

 /**
  * The screen says it, beside the vehicles it will stop.
  *
  * <p>A refusal that only exists in a log is a refusal nobody sees until the floor is quiet. The
  * communication check is what somebody reads before a shift, so what it says there is part
  * of this feature rather than a nicety: the warning while the list is still in date, and the
  * refusal once it is not, against the vehicles wired to the secured profile.
  */
 @Test void thecheckScreenSaysItBeforeAndAfterTheListRunsOut() throws Exception {
  int port=freePort();
  listenFor(port,true,GATEWAY);               // a secured profile, with vehicles on it
  X509CRL list;
  try(var in=Files.newInputStream(dir.resolve("revoked").resolve("ca.crl"))){
   list=(X509CRL)java.security.cert.CertificateFactory.getInstance("X.509").generateCRL(in);
  }
  var runsOut=list.getNextUpdate().toInstant();

  assertThat(codesSeenBy(asOf(runsOut.minus(java.time.Duration.ofDays(30)))))
   .as("in date: nothing to say about the list").doesNotContain("TLS_CRL_EXPIRES_SOON","TLS_CRL_STALE");
  assertThat(codesSeenBy(asOf(runsOut.minus(java.time.Duration.ofDays(3)))))
   .as("a week out").contains("TLS_CRL_EXPIRES_SOON");
  assertThat(codesSeenBy(asOf(runsOut.plusSeconds(1))))
   .as("and after: the links will not open").contains("TLS_CRL_STALE");
  assertThat(codesSeenBy(withLists("")))
   .as("a site that never asked for revocation checking hears nothing about it")
   .doesNotContain("TLS_CRL_EXPIRES_SOON","TLS_CRL_STALE","TLS_CRL_MISSING");
 }
 /** What the check would report with that material behind it. */
 private List<String> codesSeenBy(DeviceTls material){
  return new com.amhs.integration.CommunicationConfigurationCheck(siteOptions,fleet,wiring,
    connections,protocols,vdaProfiles,material)
   .inspect(SITE).issues().stream()
   .map(com.amhs.integration.CommunicationConfigurationCheck.Issue::code).toList();
 }

 /** This server's material, judged on a given date. */
 private static DeviceTls asOf(java.time.Instant at){
  return new DeviceTls(dir.resolve("server.p12").toString(),PASS,"",
   dir.resolve("trust.p12").toString(),PASS,dir.resolve("revoked").toString(),
   java.time.Clock.fixed(at,java.time.ZoneOffset.UTC));
 }
 /** This server's material, with the lists named differently. */
 private static DeviceTls withLists(String crls){
  return new DeviceTls(dir.resolve("server.p12").toString(),PASS,"",
   dir.resolve("trust.p12").toString(),PASS,crls);
 }
}
