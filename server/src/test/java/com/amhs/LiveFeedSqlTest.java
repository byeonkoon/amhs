package com.amhs;
import com.amhs.fleet.*;
import com.amhs.live.LiveFeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Screens follow a site over a WebSocket: a snapshot first, then numbered deltas of what changed.
 * Two screens see the same numbers and the same state; one closing leaves the other (and the site)
 * as they were; asking again gives a snapshot at the current number; a quiet site sends heartbeats
 * with the number unchanged; another origin, or a path that names no site, is refused.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.live.pass-ms=0","amhs.live.heartbeat-ms=200"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class LiveFeedSqlTest {
 @LocalServerPort int port;
 @Autowired LiveFeed feed; @Autowired FleetState fleet; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1, REVISION=1, AGV1=2;
 private final ObjectMapper json=new ObjectMapper();
 private final List<WebSocketSession> open=new ArrayList<>();

 @BeforeEach @AfterEach void clean() throws Exception {
  for(var s:open) if(s.isOpen()) s.close();
  open.clear();
  jdbc.update("DELETE FROM fleet.vehicle_state WHERE site_id=?",SITE);
 }

 /** A connected screen and everything it has been sent, in order. */
 private record Screen(WebSocketSession session,BlockingQueue<String> inbox){
  JsonNode next() throws Exception {
   var text=inbox.poll(5,TimeUnit.SECONDS);
   assertThat(text).as("a message within 5 s").isNotNull();
   return new ObjectMapper().readTree(text);
  }
  boolean quiet() throws Exception { return inbox.poll(300,TimeUnit.MILLISECONDS)==null; }
 }

 private Screen connect(String path,String origin) throws Exception {
  var inbox=new LinkedBlockingQueue<String>();
  var headers=new WebSocketHttpHeaders();
  if(origin!=null) headers.setOrigin(origin);
  // A snapshot of a busy site is larger than the Java client's default 8 KB message (browsers have no such limit).
  var container=jakarta.websocket.ContainerProvider.getWebSocketContainer();
  container.setDefaultMaxTextMessageBufferSize(4_000_000);
  var session=new StandardWebSocketClient(container).execute(new TextWebSocketHandler(){
   @Override protected void handleTextMessage(WebSocketSession s,TextMessage m){ inbox.add(m.getPayload()); }
  },headers,URI.create("ws://127.0.0.1:"+port+path)).get(5,TimeUnit.SECONDS);
  open.add(session);
  return new Screen(session,inbox);
 }
 private Screen connect() throws Exception { return connect("/api/v1/sites/"+SITE+"/live",null); }

 private JsonNode vehicle(JsonNode data,long id){
  for(var v:data.path("vehicles")) if(v.path("id").asLong()==id) return v;
  return null;
 }
 private void report(long nodeId){
  fleet.report(SITE,REVISION,AGV1,new VehicleReport(nodeId,true,true,true,false,"SIMULATED"));
 }

 @Test void twoScreensSeeTheSameNumbersAndOneClosingLeavesTheOtherAsItWas() throws Exception {
  report(201L);
  var a=connect(); var b=connect();
  var snapA=a.next(); var snapB=b.next();
  assertThat(snapA.path("type").asText()).isEqualTo("snapshot");
  assertThat(snapB.path("type").asText()).isEqualTo("snapshot");
  assertThat(snapA.path("epoch").asText()).isEqualTo(feed.epoch()).isEqualTo(snapB.path("epoch").asText());
  // The snapshot holds every part the screen follows.
  assertThat(snapA.path("data").fieldNames()).toIterable()
   .contains("revisionId","vehicles","holds","headings","moves","assignments","orders","deadlocks","zones","inDoubt");
  assertThat(vehicle(snapA.path("data"),AGV1).path("nodeId").asLong()).isEqualTo(201L);
  // B joined later; a delta may have come between. Both stand at the same number once B is in.
  long seq=snapB.path("seq").asLong();
  while(a.inbox().peek()!=null) seq=Math.max(seq,a.next().path("seq").asLong());
  assertThat(seq).isEqualTo(snapB.path("seq").asLong());

  report(202L);
  feed.passNow();
  var deltaA=a.next(); var deltaB=b.next();
  assertThat(deltaA.path("type").asText()).isEqualTo("delta");
  assertThat(deltaA.path("seq").asLong()).isEqualTo(seq+1).isEqualTo(deltaB.path("seq").asLong());
  assertThat(vehicle(deltaA.path("data"),AGV1).path("nodeId").asLong()).isEqualTo(202L);
  // Only what changed goes out.
  assertThat(deltaA.path("data").has("zones")).isFalse();
  assertThat(deltaA.path("data").has("deadlocks")).isFalse();

  a.session().close();
  for(int i=0;i<50&&feed.watching(SITE)!=1;i++) Thread.sleep(20);
  assertThat(feed.watching(SITE)).isEqualTo(1);
  report(203L);
  feed.passNow();
  var next=b.next();
  assertThat(next.path("seq").asLong()).isEqualTo(seq+2);
  assertThat(vehicle(next.path("data"),AGV1).path("nodeId").asLong()).isEqualTo(203L);
  assertThat(fleet.find(SITE,REVISION,AGV1).orElseThrow().nodeId()).isEqualTo(203L);
 }

 @Test void askingAgainGivesTheSnapshotAtTheCurrentNumber() throws Exception {
  report(201L);
  var a=connect();
  long seq=a.next().path("seq").asLong();
  report(204L);
  feed.passNow();
  var delta=a.next();
  assertThat(delta.path("seq").asLong()).isEqualTo(seq+1);
  a.session().sendMessage(new TextMessage("{\"type\":\"resync\"}"));
  var again=a.next();
  assertThat(again.path("type").asText()).isEqualTo("snapshot");
  assertThat(again.path("seq").asLong()).isEqualTo(seq+1);
  assertThat(vehicle(again.path("data"),AGV1).path("nodeId").asLong()).isEqualTo(204L);
  // Anything else a screen says is ignored.
  a.session().sendMessage(new TextMessage("{\"type\":\"assign\"}"));
  assertThat(a.quiet()).isTrue();
 }

 @Test void aQuietSiteSendsHeartbeatsAndAReconnectStartsFromASnapshot() throws Exception {
  report(201L);
  var a=connect();
  var first=a.next();
  long seq=first.path("seq").asLong();
  Thread.sleep(250);
  feed.passNow();
  var beat=a.next();
  assertThat(beat.path("type").asText()).isEqualTo("heartbeat");
  assertThat(beat.path("seq").asLong()).isEqualTo(seq);
  assertThat(beat.has("data")).isFalse();
  a.session().close();
  for(int i=0;i<50&&feed.watching(SITE)!=0;i++) Thread.sleep(20);
  report(205L);
  var b=connect();
  var snap=b.next();
  assertThat(snap.path("type").asText()).isEqualTo("snapshot");
  assertThat(snap.path("epoch").asText()).isEqualTo(first.path("epoch").asText());
  assertThat(snap.path("seq").asLong()).isGreaterThan(seq);
  assertThat(vehicle(snap.path("data"),AGV1).path("nodeId").asLong()).isEqualTo(205L);
 }

 @Test void anotherOriginOrAPathWithoutASiteIsRefused() {
  assertThatThrownBy(()->connect("/api/v1/sites/"+SITE+"/live","http://elsewhere.example")).isInstanceOf(Exception.class);
  assertThatThrownBy(()->connect("/api/v1/sites/abc/live",null)).isInstanceOf(Exception.class);
  assertThat(feed.watching(SITE)).isZero();
 }
}
