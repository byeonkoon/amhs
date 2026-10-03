package com.amhs.live;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Keeps every screen watching a site in step with it.
 *
 * <p>Each site being watched has one channel: the last value sent of each part and a sequence
 * number. A pass reads the site, and the parts that changed go out together as one delta with the
 * next number. A screen that joins, or asks again, is sent everything as it was last sent, with the
 * number it stands at, under the same lock as the deltas; so the next delta it sees follows on
 * from exactly that snapshot. A screen that sees a number out of turn, or a different epoch (the
 * server restarted), asks for a snapshot again. A screen that cannot keep up is disconnected
 * rather than left further and further behind; it reconnects and starts from a snapshot.
 *
 * <p>Nothing here decides anything: the site runs whether anybody watches or not, and a screen
 * closing leaves the work where it is.
 */
@Component
public class LiveFeed implements DisposableBean {
 static final Logger log=LoggerFactory.getLogger(LiveFeed.class);

 private final LiveSite site; private final ObjectMapper json;
 private final long passMs; private final int slowEvery; private final long heartbeatMs;
 /** Changes after every server start, so a screen can tell a new sequence from an old one. */
 private final String epoch=UUID.randomUUID().toString();
 private final Map<Long,Channel> channels=new ConcurrentHashMap<>();
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{
  var t=new Thread(r,"amhs-live"); t.setDaemon(true); return t;
 });
 private boolean started;

 LiveFeed(LiveSite site,ObjectMapper json,
          @Value("${amhs.live.pass-ms:250}") long passMs,
          @Value("${amhs.live.slow-every:4}") int slowEvery,
          @Value("${amhs.live.heartbeat-ms:5000}") long heartbeatMs){
  this.site=site; this.json=json; this.passMs=passMs; this.slowEvery=Math.max(1,slowEvery); this.heartbeatMs=heartbeatMs;
 }

 public String epoch(){ return epoch; }

 private static final class Channel {
  final long siteId;
  long seq;
  long passes;
  long lastSentAt=System.nanoTime();
  final Map<String,String> last=new LinkedHashMap<>();
  /**
   * Parts whose last attempted read failed and which have not been read since.
   *
   * <p>This is remembered on the channel rather than worked out per pass, because a pass is not
   * the unit freshness belongs to. Most passes read only what moves; if freshness were decided by
   * the pass, one of those would report everything current while the work and deadlock reads were
   * still failing, and the screen would go back to calling the past the present.
   */
  final Set<String> unread=new LinkedHashSet<>();
  final Set<WebSocketSession> sessions=new CopyOnWriteArraySet<>();
  Channel(long siteId){ this.siteId=siteId; }
 }

 /** A screen starts watching: it gets everything as last sent, with the number that goes with it. */
 void join(long siteId,WebSocketSession session){
  var channel=channels.computeIfAbsent(siteId,Channel::new);
  synchronized(channel){
   // Nobody was watching, so what was last sent may be old: read the site now.
   if(channel.sessions.isEmpty()) pass(channel,true);
   channel.sessions.add(session);
   send(channel,session,snapshot(channel));
  }
  start();
 }

 /** The screen saw something out of turn: everything again, as one snapshot. */
 void resync(long siteId,WebSocketSession session){
  var channel=channels.get(siteId);
  if(channel==null) return;
  synchronized(channel){
   if(channel.sessions.contains(session)) send(channel,session,snapshot(channel));
  }
 }

 void leave(long siteId,WebSocketSession session){
  var channel=channels.get(siteId);
  if(channel!=null) channel.sessions.remove(session);
 }

 /** How many screens are watching a site; for tests and diagnostics. */
 public int watching(long siteId){
  var channel=channels.get(siteId);
  return channel==null?0:channel.sessions.size();
 }

 /** One pass over every watched site now, outside the timer, reading everything; for tests. */
 public void passNow(){
  for(var channel:channels.values()) synchronized(channel){ if(!channel.sessions.isEmpty()) pass(channel,true); }
 }

 /**
  * One pass over a site now, choosing whether the slow parts are read at all; for tests.
  *
  * <p>Most real passes read only what moves, and that is exactly the case where freshness is easy
  * to get wrong, so a test has to be able to ask for one.
  */
 void passOnce(long siteId,boolean everything){
  var channel=channels.get(siteId);
  if(channel!=null) synchronized(channel){ pass(channel,everything); }
 }

 private synchronized void start(){
  if(started||passMs<=0) return;
  started=true;
  timer.scheduleWithFixedDelay(()->{
   for(var channel:channels.values()){
    if(channel.sessions.isEmpty()) continue;
    try{ synchronized(channel){ pass(channel,false); } }
    catch(RuntimeException e){ log.warn("live pass failed for site {}: {}",channel.siteId,e.getMessage()); }
   }
  },passMs,passMs,TimeUnit.MILLISECONDS);
 }

 /** Reads the site; what changed goes out as the next delta, or a heartbeat when nothing has for a while. */
 private void pass(Channel channel,boolean everything){
  boolean slow=everything||channel.last.isEmpty()||channel.passes%slowEvery==0;
  channel.passes++;
  var parts=new ArrayList<>(LiveSite.FAST);
  if(slow) parts.addAll(LiveSite.SLOW);
  var reading=site.read(channel.siteId,parts);
  // A part stops being out of date only by being read, and becomes out of date only by being
  // tried and failing. A part this pass did not ask about is left exactly as it was: reading what
  // moves quickly says nothing about work that is still unreadable.
  boolean freshnessChanged=channel.unread.removeAll(reading.parts().keySet());
  freshnessChanged|=channel.unread.addAll(reading.failed());

  var changed=new LinkedHashMap<String,String>();
  reading.parts().forEach((part,value)->{
   String text=write(value);
   if(text!=null&&!text.equals(channel.last.get(part))) changed.put(part,text);
  });
  if(!changed.isEmpty()){
   channel.seq++;
   channel.last.putAll(changed);
   // Every message carries what is unread, so this one says it too and nothing extra is needed.
   broadcast(channel,message("delta",channel,changed));
  }else if(freshnessChanged||System.nanoTime()-channel.lastSentAt>=heartbeatMs*1_000_000L){
   // Freshness changing is worth a message of its own and does not wait for the heartbeat: on a
   // quiet site a recovered read produces no delta, and without this the screen would stay
   // marked out of date for as long as nothing happened to move.
   broadcast(channel,message("heartbeat",channel,Map.of()));
  }
 }

 private String snapshot(Channel channel){ return message("snapshot",channel,channel.last); }

 /**
  * Parts are already JSON text, so the message is put together around them rather than re-read.
  *
  * <p>Every message names what is unread. Freshness is a property of the channel, not of the kind
  * of message, so a screen never has to infer it from whether data happened to change -- which is
  * the one thing it cannot be inferred from.
  */
 private String message(String type,Channel channel,Map<String,String> parts){
  var out=new StringBuilder(256);
  out.append("{\"type\":\"").append(type).append("\",\"epoch\":\"").append(epoch)
   .append("\",\"seq\":").append(channel.seq)
   .append(",\"at\":\"").append(OffsetDateTime.now()).append('"');
  out.append(",\"unread\":[");
  boolean firstUnread=true;
  for(String part:channel.unread){
   if(!firstUnread) out.append(',');
   firstUnread=false;
   out.append('"').append(part).append('"');
  }
  out.append(']');
  // A heartbeat carries no data: it says only where the channel stands and what it cannot read.
  if(!"heartbeat".equals(type)){
   out.append(",\"data\":{");
   boolean first=true;
   for(var e:parts.entrySet()){
    if(!first) out.append(',');
    first=false;
    out.append('"').append(e.getKey()).append("\":").append(e.getValue());
   }
   out.append('}');
  }
  return out.append('}').toString();
 }

 private void broadcast(Channel channel,String text){
  channel.lastSentAt=System.nanoTime();
  for(var session:channel.sessions) send(channel,session,text);
 }

 private void send(Channel channel,WebSocketSession session,String text){
  try{
   if(session.isOpen()) session.sendMessage(new TextMessage(text));
   else channel.sessions.remove(session);
  }catch(Exception e){
   // Too slow or gone: let it go; it reconnects and starts again from a snapshot.
   channel.sessions.remove(session);
   try{ session.close(CloseStatus.SESSION_NOT_RELIABLE); }catch(Exception ignored){ }
  }
 }

 private String write(Object value){
  try{ return json.writeValueAsString(value); }
  catch(JsonProcessingException e){ log.debug("live part not written: {}",e.getMessage()); return null; }
 }

 @Override public void destroy(){ timer.shutdownNow(); }
}
