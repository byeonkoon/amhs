package com.amhs.platform;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Who may do what.
 *
 * <p>A token is generated here, shown once, and kept only as a hash. Nothing in this class can
 * hand a token back afterwards, so an account list and an audit trail cannot leak one.
 *
 * <p>Unlike the track and the equipment, changing an account does not need the site in
 * maintenance. An account is how someone gets in, and the moment you most need to revoke one is
 * while the site is running; a rule that forced a shutdown first would be a reason to keep a
 * compromised token alive. The administrator role is the guard here, not the site's mode.
 */
@Service
public class AccountDirectory {
 private static final SecureRandom RANDOM=new SecureRandom();
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public AccountDirectory(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}

 /** An account together with the token just issued for it. The token appears here and nowhere else. */
 public record Issued(AccountView account,String token){}

 @Transactional(readOnly=true)
 public List<AccountView> list(long site){
  guard.requireSite(site);
  return jdbc.query(SELECT+" WHERE site_id=? ORDER BY id",VIEW,site);
 }

 @Transactional
 public Issued create(long site,AccountSettings s){
  guard.requireSite(site); s.validate();
  long id=guard.nextId();
  String token=newToken();
  try{
   jdbc.update("INSERT INTO platform.account(id,site_id,code,display_name,role,token_sha256,enabled)"
    +" VALUES(?,?,?,?,?,?,?)",id,site,s.code(),s.displayName(),s.parsedRole().name(),
    PrincipalResolver.sha256Hex(token),s.enabled());
  }catch(DuplicateKeyException e){
   throw new ConfigurationException(409,"ACCOUNT_CODE_IN_USE");
  }
  return new Issued(account(site,id),token);
 }

 @Transactional
 public AccountView update(long site,long id,long expectedVersion,AccountSettings s){
  guard.requireSite(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  var current=account(site,id);
  boolean losesAdmin=current.role()==Role.ADMIN&&(s.parsedRole()!=Role.ADMIN||!s.enabled());
  // Taking away the last way in is not a change anybody means to make, and it cannot be undone
  // from inside: there would be nobody left who may hand the authority back.
  if(losesAdmin&&current.enabled()&&otherEnabledAdmins(site,id)==0)
   throw new ConfigurationException(409,"LAST_ADMIN");
  try{
   guard.changed(jdbc.update("UPDATE platform.account SET code=?,display_name=?,role=?,enabled=?,version=version+1"
    +" WHERE site_id=? AND id=? AND version=?",
    s.code(),s.displayName(),s.parsedRole().name(),s.enabled(),site,id,expectedVersion));
  }catch(DuplicateKeyException e){
   throw new ConfigurationException(409,"ACCOUNT_CODE_IN_USE");
  }
  return account(site,id);
 }

 /** Replaces the token. The previous one stops working the moment this commits. */
 @Transactional
 public Issued reissueToken(long site,long id){
  guard.requireSite(site); account(site,id);
  String token=newToken();
  jdbc.update("UPDATE platform.account SET token_sha256=?,version=version+1 WHERE site_id=? AND id=?",
   PrincipalResolver.sha256Hex(token),site,id);
  return new Issued(account(site,id),token);
 }

 /** Removes the token without touching the account, so access stops but the record and its audit stay. */
 @Transactional
 public AccountView revokeToken(long site,long id){
  guard.requireSite(site); account(site,id);
  jdbc.update("UPDATE platform.account SET token_sha256=NULL,version=version+1 WHERE site_id=? AND id=?",site,id);
  return account(site,id);
 }

 private int otherEnabledAdmins(long site,long exceptId){
  return jdbc.queryForObject("SELECT count(*) FROM platform.account"
   +" WHERE site_id=? AND id<>? AND role='ADMIN' AND enabled=TRUE",Integer.class,site,exceptId);
 }
 private AccountView account(long site,long id){
  var rows=jdbc.query(SELECT+" WHERE site_id=? AND id=?",VIEW,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"ACCOUNT_NOT_FOUND");
  return rows.getFirst();
 }
 /**
  * 32 random bytes. Long enough that guessing is not a strategy, and generated here so a weak one
  * cannot be chosen by hand.
  */
 private static String newToken(){
  var bytes=new byte[32]; RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 // token_sha256 is read only as a yes/no: the hash itself never leaves this query.
 private static final String SELECT="SELECT id,site_id,code,display_name,role,enabled,"
  +"token_sha256 IS NOT NULL,version FROM platform.account";
 private static final RowMapper<AccountView> VIEW=(r,n)->new AccountView(
  r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),Role.valueOf(r.getString(5)),
  r.getBoolean(6),r.getBoolean(7),r.getLong(8));
}
