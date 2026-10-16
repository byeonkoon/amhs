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
 @Autowired com.amhs.integration.HostNotices notices;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
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
  /** Set to keep an attempt in flight: the request is recorded, then waits here to be let go. */
  volatile CountDownLatch gate=null;
  /**
   * Several at once, deliberately.
   *
   * <p>A host that answers one request at a time would queue a second attempt instead of
   * recording it, and a test asking whether something was said twice would then pass by
   * accident.
   */
  private final ExecutorService pool=Executors.newFixedThreadPool(4);
  Upstairs() throws IOException {
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.setExecutor(pool);
   server.createContext("/notices",exchange->{
    var body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
    heard.add(body);
    var key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
    if(key!=null) keys.add(key);
    var held=gate;
    if(held!=null) try{ held.await(5,TimeUnit.SECONDS); }catch(InterruptedException ignored){ }
    int code=failFirst.getAndUpdate(n->n>0?n-1:0)>0?500:answer;
    exchange.sendResponseHeaders(code,-1);
    exchange.close();
   });
   server.start();
  }
  String url(){ return "http://127.0.0.1:"+server.getAddress().getPort()+"/notices"; }
  public void close(){ server.stop(0); pool.shutdownNow(); }
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
 /**
  * A notice as if an order had just ended -- through the real path, not an INSERT of its own.
  *
  * <p>The ledger calls this inside the transaction that changed the order, so the test does the
  * same. It also means the body under test is the body that would really be sent.
  */
 private void orderEnded(String status){
  tx.executeWithoutResult(s->notices.announce(SITE,orderId,status,null));
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

   // An empty string is the one way to take it away, and it has to be: without it a credential
   // could be set and never removed.
   hosts.save(SITE,new HostEndpointSettings(upstairs.url(),null,"",2500,4,true));
   assertThat(jdbc.queryForObject("SELECT auth_value FROM integration.host_endpoint WHERE site_id=?",
    String.class,SITE)).as("explicitly cleared").isNull();
   hosts.save(SITE,new HostEndpointSettings(upstairs.url(),"X-Test-Auth","secret-value",2500,4,true));

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

 /**
  * A header the HTTP client will not send is refused when it is saved.
  *
  * <p>Asked of the client itself rather than checked against a list: {@code Host}, {@code Connection}
  * and the rest are the client's rules, and a copy of them here would drift.
  */
 @Test void aheaderTheClientWillNotSendIsRefusedAtTheSettings(){
  assertThatThrownBy(()->hosts.save(SITE,new HostEndpointSettings("http://127.0.0.1/x","Host","wms",2000,4,true)))
   .hasMessageContaining("HOST_AUTH_HEADER_NOT_ALLOWED");
  assertThatThrownBy(()->hosts.save(SITE,new HostEndpointSettings("http://127.0.0.1/x","Connection","close",2000,4,true)))
   .hasMessageContaining("HOST_AUTH_HEADER_NOT_ALLOWED");
 }

 /**
  * Settings that cannot be made into a request at all settle the notice rather than stalling it.
  *
  * <p>Written straight to the row, because the settings service refuses this now -- what is being
  * proved is that a row which somehow holds such settings does not leave every notice at PENDING
  * with nought attempts, which is a queue that never moves and never says why.
  */
 @Test void settingsThatCannotBeSentAreSettledNotStalled() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   jdbc.update("UPDATE integration.host_endpoint SET auth_header='Host' WHERE site_id=?",SITE);
   orderEnded("COMPLETED");
   assertThat(waitFor(()->"REFUSED".equals(stateNow()))).as("settled, not stuck").isTrue();
   var row=jdbc.queryForMap("SELECT attempts,last_error FROM integration.host_notice WHERE site_id=?",SITE);
   assertThat((Integer)row.get("attempts")).isGreaterThan(0);
   assertThat((String)row.get("last_error")).contains("unusable host settings");
  }
 }

 /**
  * One order's notices leave in the order they were written.
  *
  * <p>The case this is for: recovery fails and backs off, completion is written next and would go
  * straight through, and the retry of the first then succeeds -- leaving the system upstairs told
  * that an order it saw finish needs recovering. The later notice waits instead.
  */
 @Test void alaterNoticeDoesNotOvertakeAnEarlierOneForTheSameOrder() throws Exception {
  try(var upstairs=new Upstairs()){
   upstairs.answer=503;                           // nothing gets through at first
   hosts.save(SITE,new HostEndpointSettings(upstairs.url(),null,null,500,8,true));
   orderEnded("RECOVERY_REQUIRED");
   assertThat(waitFor(()->attemptsFor("RECOVERY_REQUIRED")>0)).as("the first was tried").isTrue();

   // The order finishes while the first notice is waiting to be retried.
   orderEnded("COMPLETED");
   upstairs.answer=200;
   upstairs.heard.clear();

   assertThat(waitFor(()->"SENT".equals(stateFor("COMPLETED")))).as("both got there").isTrue();
   assertThat(stateFor("RECOVERY_REQUIRED")).as("the earlier one went first").isEqualTo("SENT");

   var order=new java.util.ArrayList<String>();
   String body;
   while((body=upstairs.heard.poll(200,TimeUnit.MILLISECONDS))!=null)
    order.add(body.contains("RECOVERY_REQUIRED")?"RECOVERY_REQUIRED":"COMPLETED");
   assertThat(order).as("recovery before completion, never the other way")
    .containsExactly("RECOVERY_REQUIRED","COMPLETED");
  }
 }

 /**
  * Two sweeps at once say it once.
  *
  * <p>This is the one the fault injection found. The sweep used to read every due notice and
  * settle it after the HTTP call, so for as long as that call took, the row still looked due:
  * another sweep read it and the system upstairs heard the same thing twice. One instance kept
  * its own ticks apart with a flag, which is why it only showed up where two sweeps really can
  * overlap -- and two instances of this application in a plant are exactly that.
  *
  * <p>So one attempt is held in flight while a second sweep runs. The second must find nothing to
  * claim.
  */
 @Test void twosweepsAtOnceSayItOnce() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   orderEnded("COMPLETED");
   var held=new CountDownLatch(1);
   upstairs.gate=held;

   var inFlight=Executors.newSingleThreadExecutor();
   try{
    var first=inFlight.submit(()->{ notifier.sweep(); return null; });
    // The attempt is now at the host and waiting to be let go.
    for(int i=0;i<100&&upstairs.heard.isEmpty();i++) Thread.sleep(20);
    assertThat(upstairs.heard).as("an attempt is in flight").hasSize(1);

    notifier.sweep();                              // the second sweep, while the first is waiting
    notifier.sweep();
    assertThat(upstairs.heard).as("the second sweep found nothing to claim").hasSize(1);
    assertThat(stateNow()).as("claimed while it is being said").isEqualTo("SENDING");

    upstairs.gate=null; held.countDown();
    first.get(5,TimeUnit.SECONDS);
   }finally{ inFlight.shutdownNow(); }

   assertThat(stateNow()).isEqualTo("SENT");
   assertThat(upstairs.heard).as("said once, by one of them").hasSize(1);
   assertThat(jdbc.queryForObject("SELECT attempts FROM integration.host_notice WHERE site_id=?",
    Integer.class,SITE)).as("one attempt, counted once").isEqualTo(1);
  }
 }

 /**
  * A claim nobody is working on any more is said again, with the same key.
  *
  * <p>A sender killed between claiming a notice and answering for it leaves one in SENDING with
  * nothing coming. That is the same "nobody can say" a device command leaves behind, with the one
  * difference that matters: this carries an {@code Idempotency-Key} the far side is expected to
  * recognise, so saying it again is the right thing rather than the forbidden thing.
  */
 @Test void aclaimNobodyIsWorkingOnIsSaidAgain() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   orderEnded("COMPLETED");
   // As a sender that died mid-attempt would have left it: claimed, counted, long ago.
   jdbc.update("UPDATE integration.host_notice SET state='SENDING',attempts=1,"
    +"claimed_at=clock_timestamp()-make_interval(mins=>10) WHERE site_id=?",SITE);

   assertThat(waitFor(()->"SENT".equals(stateNow()))).as("it was said again").isTrue();
   assertThat(upstairs.keys.poll(2,TimeUnit.SECONDS)).as("the same key, so a repeat is recognisable")
    .isEqualTo("order-"+orderId+"-COMPLETED");
  }
 }

 /** Which transition it is, so a host that reorders them for its own reasons can still tell. */
 @Test void anoticeSaysWhichTransitionOfTheOrderItIs() throws Exception {
  try(var upstairs=new Upstairs()){
   sendTo(upstairs);
   orderEnded("COMPLETED");
   assertThat(waitFor(()->"SENT".equals(stateNow()))).isTrue();
   assertThat(upstairs.heard.poll(2,TimeUnit.SECONDS)).contains("\"sequence\"");
  }
 }

 private String stateFor(String status){
  var rows=jdbc.query("SELECT state FROM integration.host_notice WHERE site_id=? AND order_status=?",
   (r,n)->r.getString(1),SITE,status);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private int attemptsFor(String status){
  var rows=jdbc.query("SELECT attempts FROM integration.host_notice WHERE site_id=? AND order_status=?",
   (r,n)->r.getInt(1),SITE,status);
  return rows.isEmpty()?0:rows.getFirst();
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
