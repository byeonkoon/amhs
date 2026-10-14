package com.amhs.integration.infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Says what is owed to the system that asked for the work.
 *
 * <p>Apart from the writing, deliberately. A transition commits without waiting on somebody else's
 * web server, and a web server that is down does not stop vehicles from moving.
 *
 * <p>What an attempt may claim is the same three-valued problem a device command has, and the
 * answer has the same shape:
 *
 * <ul>
 * <li><b>2xx</b> -- taken. Nothing more is owed.</li>
 * <li><b>4xx</b> -- refused, and refused is an answer. Repeating it would be refused again, so it
 *     is kept as it is and said in the log rather than retried until the attempts run out. The one
 *     exception is 408 and 429, which are the far side asking for later rather than saying no.</li>
 * <li><b>5xx, a timeout, a broken connection</b> -- nobody knows. It may have been taken and the
 *     answer lost, so the retry carries the same {@code Idempotency-Key} and the far side is
 *     expected to recognise it. Out of attempts it becomes ABANDONED, which is not "failed": it is
 *     this controller saying it cannot tell, which is the truth.</li>
 * </ul>
 */
@Component
public class HostNotifier {
 private static final Logger log=LoggerFactory.getLogger(HostNotifier.class);
 private final JdbcTemplate jdbc; private final HttpClient http; private final int batch;

 HostNotifier(JdbcTemplate jdbc,@Value("${amhs.host.batch:20}") int batch){
  this.jdbc=jdbc; this.batch=batch;
  this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
   .followRedirects(HttpClient.Redirect.NEVER)   // a redirect would drop the auth header
   .build();
 }

 /** One endpoint as configured, with the secret it authenticates with. */
 private record Endpoint(long siteId,String url,String authHeader,String authValue,int timeoutMs,int maxAttempts){}
 /** One thing waiting to be said. */
 private record Notice(long id,String key,String body,int attempts){}

 /**
  * Sends what is due, for every site that has somewhere to send it.
  *
  * <p>Public so a test can step it; otherwise the schedule is the only caller.
  */
 @Scheduled(fixedDelayString="${amhs.host.tick-ms:1000}")
 public void sweep(){
  List<Endpoint> endpoints;
  try{
   endpoints=jdbc.query("SELECT site_id,url,auth_header,auth_value,timeout_ms,max_attempts"
    +" FROM integration.host_endpoint WHERE enabled",
    (r,n)->new Endpoint(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6)));
  }catch(RuntimeException e){ log.warn("could not read where to send notices: {}",e.getMessage()); return; }
  for(var endpoint:endpoints) send(endpoint);
 }

 private void send(Endpoint endpoint){
  var due=jdbc.query("SELECT id,notice_key,body,attempts FROM integration.host_notice"
   +" WHERE site_id=? AND state='PENDING' AND next_attempt_at<=clock_timestamp()"
   +" ORDER BY created_at LIMIT ?",
   (r,n)->new Notice(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4)),endpoint.siteId(),batch);
  for(var notice:due) attempt(endpoint,notice);
 }

 private void attempt(Endpoint endpoint,Notice notice){
  var request=HttpRequest.newBuilder(URI.create(endpoint.url()))
   .timeout(Duration.ofMillis(endpoint.timeoutMs()))
   .header("Content-Type","application/json")
   // The same courtesy this controller asks for when orders come in: a key the far side can use to
   // recognise a repeat, because a retry after a lost answer is exactly what it is for.
   .header("Idempotency-Key",notice.key())
   .POST(HttpRequest.BodyPublishers.ofString(notice.body()));
  if(endpoint.authHeader()!=null&&!endpoint.authHeader().isBlank()&&endpoint.authValue()!=null)
   request.header(endpoint.authHeader(),endpoint.authValue());

  int attempts=notice.attempts()+1;
  try{
   var answer=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
   int code=answer.statusCode();
   if(code>=200&&code<300){ settle(notice.id(),"SENT",attempts,null); return; }
   // Asking for later is not refusing.
   if(code==408||code==429){ retryLater(notice.id(),attempts,endpoint.maxAttempts(),"HTTP "+code); return; }
   if(code>=400&&code<500){
    log.warn("host refused notice {} with HTTP {}; not retrying a refusal",notice.key(),code);
    settle(notice.id(),"REFUSED",attempts,"HTTP "+code);
    return;
   }
   retryLater(notice.id(),attempts,endpoint.maxAttempts(),"HTTP "+code);
  }catch(InterruptedException e){
   Thread.currentThread().interrupt();
   retryLater(notice.id(),attempts,endpoint.maxAttempts(),"interrupted");
  }catch(Exception e){
   // Written and unanswered. It may have been taken, which is why this is a retry and not a failure.
   retryLater(notice.id(),attempts,endpoint.maxAttempts(),cut(e.getMessage()));
  }
 }

 private void settle(long id,String state,int attempts,String error){
  jdbc.update("UPDATE integration.host_notice SET state=?,attempts=?,last_error=?,"
   +"settled_at=clock_timestamp() WHERE id=?",state,attempts,error,id);
 }

 /**
  * Backs off and tries again, or gives up saying it cannot tell.
  *
  * <p>The wait doubles, which is what keeps a site whose host is down from spending its evening
  * reconnecting; the cap stops it drifting out to hours.
  */
 private void retryLater(long id,int attempts,int maxAttempts,String error){
  if(attempts>=maxAttempts){
   log.error("giving up on notice {} after {} attempts; nobody can say whether it arrived",id,attempts);
   settle(id,"ABANDONED",attempts,cut(error));
   return;
  }
  long seconds=Math.min(300,(long)Math.pow(2,Math.min(attempts,8)));
  jdbc.update("UPDATE integration.host_notice SET attempts=?,last_error=?,"
   +"next_attempt_at=clock_timestamp()+make_interval(secs=>?) WHERE id=?",
   attempts,cut(error),(double)seconds,id);
 }
 private static String cut(String s){ return s==null?null:s.length()<=256?s:s.substring(0,256); }
}
