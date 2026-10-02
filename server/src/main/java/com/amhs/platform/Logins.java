package com.amhs.platform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Logging in, staying logged in, and logging out.
 *
 * <p>A login exchanges something the person knows for something the browser holds for a while. The
 * session value is generated here, returned once, and kept only as a hash — the same reason the
 * password is not kept: what is stored cannot be replayed as a login.
 *
 * <p>A wrong password and an unknown account are refused identically. Telling them apart tells
 * somebody which names exist, which is the first half of guessing a password.
 */
@Service
public class Logins {
 private static final SecureRandom RANDOM=new SecureRandom();
 private final JdbcTemplate jdbc; private final Duration life;
 public Logins(JdbcTemplate jdbc,@Value("${amhs.session.hours:12}") long hours){
  this.jdbc=jdbc; this.life=Duration.ofHours(Math.max(hours,1));
 }

 /** The session value appears here and nowhere else; afterwards only its hash exists. */
 public record Session(String token,Principal principal,OffsetDateTime expiresAt){}

 @Transactional
 public Session logIn(long siteId,String code,String password){
  var rows=jdbc.query("SELECT id,site_id,code,role,password_hash,enabled FROM platform.account"
   +" WHERE site_id=? AND code=?",
   (r,n)->new Candidate(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getBoolean(6)),
   siteId,code);
  var found=rows.isEmpty()?null:rows.getFirst();
  // The same refusal whichever it was, and the work is done either way so the timing does not
  // separate "no such account" from "wrong password".
  boolean ok=Passwords.matches(password,found==null?null:found.passwordHash())
   &&found!=null&&found.enabled();
  if(!ok) throw new ConfigurationException(401,"LOGIN_REFUSED");

  String token=newToken();
  var expires=OffsetDateTime.now().plus(life);
  jdbc.update("INSERT INTO platform.user_session(account_id,token_sha256,expires_at) VALUES(?,?,?)",
   found.id(),PrincipalResolver.sha256Hex(token),expires);
  return new Session(token,new Principal(found.id(),found.siteId(),found.code(),Role.of(found.role())),expires);
 }

 /**
  * Who a live session belongs to. An expired or ended one is nobody, and the account's current
  * role and enabled state are read now rather than remembered from the login: taking authority
  * away has to take effect without waiting for a session to run out.
  */
 @Transactional
 public Optional<Principal> resolve(String tokenSha256Hex){
  if(tokenSha256Hex==null||tokenSha256Hex.length()!=64) return Optional.empty();
  var rows=jdbc.query("SELECT a.id,a.site_id,a.code,a.role FROM platform.user_session s"
   +" JOIN platform.account a ON a.id=s.account_id"
   +" WHERE s.token_sha256=? AND s.ended_at IS NULL AND s.expires_at>now() AND a.enabled=TRUE",
   (r,n)->new Principal(r.getLong(1),r.getLong(2),r.getString(3),Role.valueOf(r.getString(4))),tokenSha256Hex);
  if(rows.isEmpty()) return Optional.empty();
  jdbc.update("UPDATE platform.user_session SET last_seen_at=now() WHERE token_sha256=?",tokenSha256Hex);
  return Optional.of(rows.getFirst());
 }

 @Transactional
 public void logOut(String tokenSha256Hex){
  if(tokenSha256Hex==null) return;
  jdbc.update("UPDATE platform.user_session SET ended_at=now(),end_reason='LOGOUT'"
   +" WHERE token_sha256=? AND ended_at IS NULL",tokenSha256Hex);
 }

 /** Ends every live session of an account, used when its password changes or it is switched off. */
 @Transactional
 public int endSessionsOf(long accountId,String reason){
  return jdbc.update("UPDATE platform.user_session SET ended_at=now(),end_reason=?"
   +" WHERE account_id=? AND ended_at IS NULL",reason,accountId);
 }

 private record Candidate(long id,long siteId,String code,String role,String passwordHash,boolean enabled){}
 private static String newToken(){
  var bytes=new byte[32]; RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
}
