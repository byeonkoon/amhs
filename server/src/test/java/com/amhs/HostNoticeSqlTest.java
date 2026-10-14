package com.amhs;
import com.amhs.integration.HostEndpointConfiguration;
import com.amhs.integration.HostEndpointSettings;
import com.amhs.integration.infrastructure.HostNotifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/**
 * Telling the system that asked for the work how it ended.
 *
 * <p>Orders come in over REST and nothing went back out, so the system upstairs had to keep asking.
 * What is proved here is the contract an attempt settles on -- taken, refused, or nobody can tell --
 * and that a notice is written beside the transition rather than instead of it.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class HostNoticeSqlTest {
 @Autowired HostEndpointConfiguration hosts; @Autowired HostNotifier notifier;
 @Autowired JdbcTemplate jdbc;
 private static final long SITE=1;
 private long orderId;

 /** A system upstairs: answers what the test tells it to, and keeps what it was sent. */
 private static final class Upstairs implements AutoCloseable {
  final HttpServer server;
  final BlockingQueue<String> heard=new LinkedBlockingQueue<>();
  final BlockingQueue<String> keys=new LinkedBlockingQueue<>();
  volatile int answer=200;
  /** Answers this many times before switching to `answer`, which is how a flaky host is played. */
  final AtomicInteger failFirst=new AtomicInteger(0);
  Upstairs() throws IOException {
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/notices",exchange->{
    var body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
    heard.add(body);
    var key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
    if(key!=null) keys.add(key);
    int code=failFirst.getAndUpdate(n->n>0?n-1:0)>0?500:answer;
    exchange.sendResponseHeaders(code,-1);
    exchange.close();
   });
   server.start();
  }
  String url(){ return "http://127.0.0.1:"+server.getAddress().getPort()+"/notices"; }
  public void close(){ server.stop(0); }
 }

 @BeforeEach void anOrderToTalkAbout(){
  clean();
  jdbc.update("UPDATE platform.site_operation SET mode='MAINTENANCE' WHERE site_id=?",SITE);
  // An order of this test's own, straight to the ledger: what is being proved is the notice, not
  // how an order gets made.
  var ports=jdbc.query("SELECT id FROM topology.port WHERE site_id=? ORDER BY id LIMIT 2",
   (r,n)->r.getLong(1),SITE);
  orderId=jdbc.queryForObject("INSERT INTO transport.order(site_id,revision_id,request_source,"
   +"idempotency_key,request_fingerprint,source_port_id,destination_port_id,vehicle_type,carrier_code,"
   +"priority,status) VALUES(?,(SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'),"
   +"'host-test',?,repeat('0',64),?,?,'OHT','CAR-NOTICE',50,'QUEUED') RETURNING id",
   Long.class,SITE,SITE,"notice-test-"+System.nanoTime(),ports.get(0),ports.get(1));
 }
 @AfterEach void clean(){
  jdbc.update("DELETE FROM integration.host_notice WHERE site_id=?",SITE);
  jdbc.update("DELETE FROM integration.host_endpoint WHERE site_id=?",SITE);
  if(orderId>0){
   jdbc.update("DELETE FROM transport.order_event WHERE order_id=?",orderId);
   jdbc.update("DELETE FROM transport.assignment WHERE order_id=?",orderId);
   jdbc.update("DELETE FROM transport.order WHERE id=?",orderId);
   orderId=0;
  }
 }

 private void sendTo(Upstairs upstairs){
  hosts.save(SITE,new HostEndpointSettings(upstairs.url(),"X-Test-Auth","secret-value",2000,4,true));
 }
 /** A notice as if an order had just ended, written the way the ledger writes one. */
 private void orderEnded(String status){
  jdbc.update("INSERT INTO integration.host_notice(site_id,order_id,notice_key,order_status,body)"
   +" VALUES(?,?,?,?,?) ON CONFLICT(notice_key) DO NOTHING",
   SITE,orderId,"order-"+orderId+"-"+status,status,
   "{\"event\":\"transport-order\",\"orderId\":"+orderId+",\"status\":\""+status+"\"}");
 }
 private String stateNow(){
  var rows=jdbc.query("SELECT state FROM integration.host_notice WHERE site_id=? ORDER BY id DESC LIMIT 1",
   (r,n)->r.getString(1),SITE);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private boolean waitFor(java.util.function.BooleanSupplier until) throws InterruptedException {
  for(int i=0;i<100;i++){
   notifier.sweep();
   if(until.getAsBoolean()) return true;
   Thread.sleep(50);
  }
  return false;
 }

 @Test void whatTheHostTakesIsSettledAndSaidOnlyOnce() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   orderEnded("COMPLETED");
   assertThat(waitFor(()->"SENT".equals(stateNow()))).as("the host took it").isTrue();

   var body=upstairs.heard.poll(2,TimeUnit.SECONDS);
   assertThat(body).isNotNull().contains("\"status\":\"COMPLETED\"").contains(String.valueOf(orderId));
   assertThat(upstairs.keys.poll(1,TimeUnit.SECONDS)).as("a key the far side can recognise a repeat by")
    .isEqualTo("order-"+orderId+"-COMPLETED");

   // Settled is settled: more sweeps must not say it again.
   notifier.sweep(); notifier.sweep();
   assertThat(upstairs.heard.poll(300,TimeUnit.MILLISECONDS)).as("nothing was repeated").isNull();
  }
 }

 /**
  * A refusal is an answer, and repeating it would be refused again.
  *
  * <p>So it is kept as it is rather than retried until the attempts run out -- the difference
  * between "they said no" and "nobody could tell", which is the whole point of having both.
  */
 @Test void arefusalIsKeptRatherThanRetried() throws Exception {
  try(var upstairs=new Upstairs()){
   upstairs.answer=400;
   sendTo(upstairs);
   orderEnded("CANCELLED");
   assertThat(waitFor(()->"REFUSED".equals(stateNow()))).isTrue();
   var attempts=jdbc.queryForObject("SELECT attempts FROM integration.host_notice WHERE site_id=?",
    Integer.class,SITE);
   assertThat(attempts).as("refused once, not four times").isEqualTo(1);
  }
 }

 /** A host that was briefly down is retried, carrying the same key so a repeat is recognisable. */
 @Test void ahostThatWasDownIsTriedAgainWithTheSameKey() throws Exception {
  try(var upstairs=new Upstairs()){
   // One failure, not two: the wait doubles, so a second would put the retry past what this
   // test waits for. What is being proved is that a retry happens and carries the same key.
   upstairs.failFirst.set(1);
   sendTo(upstairs);
   orderEnded("COMPLETED");
   assertThat(waitFor(()->"SENT".equals(stateNow()))).as("it got there in the end").isTrue();

   var seen=new java.util.ArrayList<String>();
   String key;
   while((key=upstairs.keys.poll(200,TimeUnit.MILLISECONDS))!=null) seen.add(key);
   assertThat(seen).hasSizeGreaterThan(1).as("every attempt carried the one key")
    .containsOnly("order-"+orderId+"-COMPLETED");
  }
 }

 /** Out of attempts is not "failed": nobody can say whether it arrived, and that is its own state. */
 @Test void outOfAttemptsIsRecordedAsNobodyCanTell() throws Exception {
  try(var upstairs=new Upstairs()){
   upstairs.answer=503;
   hosts.save(SITE,new HostEndpointSettings(upstairs.url(),null,null,500,2,true));
   orderEnded("RECOVERY_REQUIRED");
   assertThat(waitFor(()->"ABANDONED".equals(stateNow()))).isTrue();
  }
 }

 /** Nothing is sent anywhere until somebody says where, and switching it off stops it. */
 @Test void withNowhereToSendNothingLeaves() throws Exception {
  orderEnded("COMPLETED");
  notifier.sweep();
  assertThat(stateNow()).as("still owed, not lost").isEqualTo("PENDING");

  try(var upstairs=new Upstairs()){
   hosts.save(SITE,new HostEndpointSettings(upstairs.url(),null,null,2000,4,false));
   notifier.sweep();
   assertThat(stateNow()).as("configured but switched off is still nowhere").isEqualTo("PENDING");
   assertThat(upstairs.heard.poll(300,TimeUnit.MILLISECONDS)).isNull();
  }
 }

 /**
  * The credential goes in and never comes back out, and editing the rest does not wipe it.
  *
  * <p>The same rule an account password follows. A screen that could read it back is a screen that
  * can leak it, and a save that cleared it would make changing a URL a way to break authentication.
  */
 @Test void thecredentialIsWriteOnlyAndSurvivesAnEdit() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   assertThat(hosts.get(SITE).orElseThrow().settings().authValue()).as("never read back").isNull();

   hosts.save(SITE,new HostEndpointSettings(upstairs.url()+"?v=2","X-Test-Auth",null,2500,4,true));
   assertThat(jdbc.queryForObject("SELECT auth_value FROM integration.host_endpoint WHERE site_id=?",
    String.class,SITE)).as("kept through an edit that did not mention it").isEqualTo("secret-value");

   orderEnded("COMPLETED");
   assertThat(waitFor(()->"SENT".equals(stateNow()))).isTrue();
   assertThat(upstairs.heard.poll(2,TimeUnit.SECONDS)).isNotNull();
  }
 }

 /** A URL this cannot dial is refused when it is saved, not when an order ends. */
 @Test void anaddressNothingCanDialIsRefusedAtTheSettings(){
  assertThatThrownBy(()->hosts.save(SITE,new HostEndpointSettings("ftp://host/notices",null,null,2000,4,true)))
   .hasMessageContaining("HOST_URL_NOT_HTTP");
 }

 /** What was said lately, for somebody trying to find out why the host has gone quiet. */
 @Test void whatWasSaidLatelyCanBeRead() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   orderEnded("COMPLETED");
   assertThat(waitFor(()->"SENT".equals(stateNow()))).isTrue();
   List<HostEndpointConfiguration.Notice> recent=hosts.recent(SITE,10);
   assertThat(recent).hasSize(1);
   assertThat(recent.getFirst().orderStatus()).isEqualTo("COMPLETED");
   assertThat(recent.getFirst().state()).isEqualTo("SENT");
   assertThat(recent.getFirst().settledAt()).isNotNull();
  }
 }
}
