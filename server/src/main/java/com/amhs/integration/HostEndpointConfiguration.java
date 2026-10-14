package com.amhs.integration;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationLock;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/**
 * Where this site tells the system that asked for the work how it ended.
 *
 * <p>One per site. More than one would be a routing rule, and nothing here knows enough about the
 * systems upstairs to route between them.
 */
@Service
public class HostEndpointConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public HostEndpointConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){ this.jdbc=jdbc; this.guard=guard; }

 /** What is configured, with the secret left out -- it never leaves this server. */
 @Transactional(readOnly=true)
 public Optional<ConfigurationRecord<HostEndpointSettings>> get(long site){
  guard.requireSite(site);
  return jdbc.query("SELECT id,version,url,auth_header,timeout_ms,max_attempts,enabled"
   +" FROM integration.host_endpoint WHERE site_id=?",
   (r,n)->new ConfigurationRecord<>(r.getLong(1),r.getLong(2),
    new HostEndpointSettings(r.getString(3),r.getString(4),null,r.getInt(5),r.getInt(6),r.getBoolean(7))),
   site).stream().findFirst();
 }

 /**
  * Sets where notices go, creating the row the first time.
  *
  * <p>A null {@code authValue} keeps whatever is stored. That is what makes it possible to change
  * the URL without being asked for the credential again -- and the only way to clear a credential
  * is to send an empty string, which says so.
  */
 @Transactional
 public ConfigurationRecord<HostEndpointSettings> save(long site,HostEndpointSettings settings){
  guard.acquire(site);
  settings.validate();
  var existing=get(site);
  if(existing.isEmpty()){
   jdbc.update("INSERT INTO integration.host_endpoint(id,site_id,url,auth_header,auth_value,timeout_ms,"
    +"max_attempts,enabled) VALUES(?,?,?,?,?,?,?,?)",guard.nextId(),site,settings.url(),
    settings.authHeader(),settings.authValue(),settings.timeoutMs(),settings.maxAttempts(),settings.enabled());
  }else{
   guard.changed(jdbc.update("UPDATE integration.host_endpoint SET url=?,auth_header=?,"
    +"auth_value=CASE WHEN CAST(? AS VARCHAR) IS NULL THEN auth_value ELSE CAST(? AS VARCHAR) END,"
    +"timeout_ms=?,max_attempts=?,enabled=?,version=version+1 WHERE site_id=?",
    settings.url(),settings.authHeader(),settings.authValue(),settings.authValue(),
    settings.timeoutMs(),settings.maxAttempts(),settings.enabled(),site));
  }
  return get(site).orElseThrow(()->new ConfigurationException(500,"HOST_ENDPOINT_NOT_SAVED"));
 }

 /** One notice as a screen may show it. The body is included; it holds nothing secret. */
 public record Notice(long id,long orderId,String orderStatus,String state,int attempts,
                      String lastError,java.time.OffsetDateTime createdAt,java.time.OffsetDateTime settledAt){}

 /**
  * What has been said lately and what became of it.
  *
  * <p>Worth a screen of its own eventually. Until then it is the only way to find out that a host
  * has been refusing everything since Tuesday, which is a thing worth being able to find out.
  */
 @Transactional(readOnly=true)
 public java.util.List<Notice> recent(long site,int limit){
  guard.requireSite(site);
  return jdbc.query("SELECT id,order_id,order_status,state,attempts,last_error,created_at,settled_at"
   +" FROM integration.host_notice WHERE site_id=? ORDER BY created_at DESC LIMIT ?",
   (r,n)->new Notice(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getInt(5),r.getString(6),
    r.getObject(7,java.time.OffsetDateTime.class),r.getObject(8,java.time.OffsetDateTime.class)),
   site,Math.max(1,Math.min(limit,200)));
 }
}
