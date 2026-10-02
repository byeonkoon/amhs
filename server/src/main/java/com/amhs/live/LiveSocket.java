package com.amhs.live;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code /api/v1/sites/{siteId}/live}: a screen's live connection. Same origin only (the
 * development proxy presents itself as the server). The only thing a screen may say is
 * {@code {"type":"resync"}}; everything else it sends is ignored.
 */
@Configuration(proxyBeanMethods=false)
@EnableWebSocket
class LiveSocket extends TextWebSocketHandler implements WebSocketConfigurer {
 private static final Pattern PATH=Pattern.compile("^/api/v1/sites/(\\d{1,18})/live$");
 private static final String SITE="amhs.live.site";

 private final LiveFeed feed; private final ObjectMapper json;
 private final int sendTimeMs; private final int bufferBytes;
 /** The decorated session for each connection, so sends from the timer and from a resync never interleave. */
 private final Map<String,WebSocketSession> sessions=new ConcurrentHashMap<>();

 LiveSocket(LiveFeed feed,ObjectMapper json,
            @Value("${amhs.live.send-time-ms:5000}") int sendTimeMs,
            @Value("${amhs.live.buffer-bytes:2000000}") int bufferBytes){
  this.feed=feed; this.json=json; this.sendTimeMs=sendTimeMs; this.bufferBytes=bufferBytes;
 }

 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
  registry.addHandler(this,"/api/v1/sites/*/live").addInterceptors(new HandshakeInterceptor(){
   @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,
     WebSocketHandler handler,Map<String,Object> attributes){
    var m=PATH.matcher(request.getURI().getPath());
    if(!m.matches()){ response.setStatusCode(HttpStatus.BAD_REQUEST); return false; }
    attributes.put(SITE,Long.parseLong(m.group(1)));
    return true;
   }
   @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,
     WebSocketHandler handler,Exception exception){ }
  });
 }

 @Override public void afterConnectionEstablished(WebSocketSession raw){
  // A screen that falls this far behind is cut off and starts again from a snapshot.
  var session=new ConcurrentWebSocketSessionDecorator(raw,sendTimeMs,bufferBytes,
   ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
  sessions.put(raw.getId(),session);
  feed.join(site(raw),session);
 }

 @Override protected void handleTextMessage(WebSocketSession raw,TextMessage message){
  var session=sessions.get(raw.getId());
  if(session==null||message.getPayloadLength()>256) return;
  try{
   var type=json.readTree(message.getPayload()).path("type").asText("");
   if("resync".equals(type)) feed.resync(site(raw),session);
  }catch(Exception ignored){ /* not something a screen says */ }
 }

 @Override public void afterConnectionClosed(WebSocketSession raw,CloseStatus status){ gone(raw); }

 @Override public void handleTransportError(WebSocketSession raw,Throwable exception){ gone(raw); }

 private void gone(WebSocketSession raw){
  var session=sessions.remove(raw.getId());
  if(session!=null) feed.leave(site(raw),session);
 }

 private static long site(WebSocketSession raw){ return (Long)raw.getAttributes().get(SITE); }
}
