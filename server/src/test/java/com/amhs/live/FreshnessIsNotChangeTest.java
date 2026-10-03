package com.amhs.live;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Whether the screen is looking at the present is a different question from whether anything moved.
 *
 * <p>Most passes read only what moves. If a pass decided freshness by itself, one of those would
 * announce everything current while the work and deadlock reads were still failing, and the screen
 * would go back to calling itself live over data the server has no way to refresh. On a quiet site
 * the opposite happens: the reads recover, the values are unchanged, so there is no delta to send
 * and nothing ever tells the screen it may trust what it shows again.
 *
 * <p>So every message names what the server cannot read, remembered per part: cleared by reading
 * that part, set by trying it and failing, and left alone by a pass that did not ask about it.
 */
class FreshnessIsNotChangeTest {
 private static final long SITE=1;
 private final ObjectMapper json=new ObjectMapper();
 /** Which parts refuse to be read, and what the others answer. Changed between passes by a test. */
 private final Set<String> failing=new HashSet<>();
 private final Map<String,Object> values=new LinkedHashMap<>();

 /** Stands in for reading the site: no database, only which parts answer and which do not. */
 private LiveSite theSite(){
  var site=Mockito.mock(LiveSite.class);
  Mockito.when(site.read(anyLong(),anyCollection())).thenAnswer(call->{
   @SuppressWarnings("unchecked") var asked=(Collection<String>)call.getArgument(1);
   var read=new LinkedHashMap<String,Object>();
   var failed=new ArrayList<String>();
   for(String part:asked){
    if(failing.contains(part)) failed.add(part);
    else read.put(part,values.getOrDefault(part,List.of()));
   }
   return new LiveSite.Reading(read,List.copyOf(failed));
  });
  return site;
 }

 /** Collects what one watching screen is sent, in order. */
 private static final class Screen {
  final List<String> sent=new ArrayList<>();
  final WebSocketSession session=Mockito.mock(WebSocketSession.class);
  Screen(){
   Mockito.when(session.isOpen()).thenReturn(true);
   try{
    Mockito.doAnswer(call->{ sent.add(((TextMessage)call.getArgument(0)).getPayload()); return null; })
     .when(session).sendMessage(any());
   }catch(IOException e){ throw new AssertionError(e); }
  }
  String last(){ return sent.get(sent.size()-1); }
 }

 /** The timer is off (no pass interval), so every pass here is one the test asked for. */
 private LiveFeed feed(){ return new LiveFeed(theSite(),json,0,4,5000); }
 private String typeOf(String message)throws Exception{ return json.readTree(message).get("type").asText(); }
 private List<String> unreadOf(String message)throws Exception{
  var out=new ArrayList<String>();
  json.readTree(message).get("unread").forEach(n->out.add(n.asText()));
  return out;
 }

 @Test void aPassThatDidNotAskAboutAPartDoesNotDeclareItCurrentAgain()throws Exception{
  failing.add("orders");
  var feed=feed();
  var screen=new Screen();
  feed.join(SITE,screen.session);
  // Joining reads everything, so the part that cannot be read is named from the start.
  assertThat(unreadOf(screen.last())).contains("orders");

  // A pass of the usual kind -- only what moves -- with a vehicle that has moved.
  values.put("vehicles",List.of(Map.of("id",1,"nodeId",11)));
  feed.passOnce(SITE,false);

  assertThat(typeOf(screen.last())).isEqualTo("delta");
  assertThat(unreadOf(screen.last()))
   .as("a vehicle moving does not make unreadable work current").contains("orders");
 }

 @Test void aQuietSiteIsToldWhenItsReadsRecoverEvenThoughNothingChanged()throws Exception{
  var feed=feed();
  var screen=new Screen();
  // Read once while everything works, so there is a known value to come back to unchanged.
  values.put("orders",List.of(Map.of("id",7,"status","MOVING")));
  feed.join(SITE,screen.session);
  assertThat(unreadOf(screen.last())).isEmpty();

  failing.add("orders");
  feed.passOnce(SITE,true);
  assertThat(unreadOf(screen.last())).containsExactly("orders");
  int before=screen.sent.size();

  // The read recovers and answers exactly what it did before, so there is no delta to send. This
  // is the quiet site: without freshness of its own, nothing would ever lift the mark.
  failing.clear();
  feed.passOnce(SITE,true);

  assertThat(screen.sent.size()).as("freshness changing is worth a message of its own")
   .isGreaterThan(before);
  assertThat(typeOf(screen.last())).isEqualTo("heartbeat");
  assertThat(unreadOf(screen.last())).as("nothing is unread any more").isEmpty();
 }

 /** The other direction: a part that starts failing is said at once, without waiting for a change. */
 @Test void aReadThatStartsFailingIsSaidWithoutWaitingForTheHeartbeat()throws Exception{
  var feed=feed();
  var screen=new Screen();
  feed.join(SITE,screen.session);
  assertThat(unreadOf(screen.last())).isEmpty();
  int before=screen.sent.size();

  failing.add("deadlocks");
  feed.passOnce(SITE,true);

  assertThat(screen.sent.size()).isGreaterThan(before);
  assertThat(unreadOf(screen.last())).containsExactly("deadlocks");
 }

 @Test void nothingIsSaidAgainWhileNothingChangesAndEverythingCanBeRead()throws Exception{
  var feed=feed();
  var screen=new Screen();
  feed.join(SITE,screen.session);
  int after=screen.sent.size();
  feed.passOnce(SITE,true);
  feed.passOnce(SITE,false);
  assertThat(screen.sent.size())
   .as("a quiet, readable site needs nothing between heartbeats").isEqualTo(after);
 }
}
