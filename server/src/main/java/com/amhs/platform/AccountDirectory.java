package com.amhs.platform;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * Who may log in, and what each may do.
 *
 * <p>A password is set here and never read back: only a salted derivation of it is stored, so
 * nothing in this class — an account list, an audit trail — can give one away.
 *
 * <p>Unlike the track and the equipment, changing an account does not need the site in
 * maintenance. An account is how someone gets in, and the moment you most need to shut one off is
 * while the site is running; a rule that forced a shutdown first would be a reason to leave a
 * compromised login working. The administrator level is the guard here, not the site's mode.
 */
@Service
public class AccountDirectory {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard; private final Logins logins;
 public AccountDirectory(JdbcTemplate jdbc,ConfigurationLock guard,Logins logins){
  this.jdbc=jdbc; this.guard=guard; this.logins=logins;
 }

 @Transactional(readOnly=true)
 public List<AccountView> list(long site){
  guard.requireSite(site);
  return jdbc.query(SELECT+" WHERE site_id=? ORDER BY id",VIEW,site);
 }

 @Transactional
 public AccountView create(long site,AccountSettings s,String password){
  guard.requireSite(site); s.validate();
  String hash=Passwords.hash(password);
  long id=guard.nextId();
  try{
   jdbc.update("INSERT INTO platform.account(id,site_id,code,display_name,role,password_hash,enabled)"
    +" VALUES(?,?,?,?,?,?,?)",id,site,s.code(),s.displayName(),s.parsedRole().name(),hash,s.enabled());
  }catch(DuplicateKeyException e){
   throw new ConfigurationException(409,"ACCOUNT_CODE_IN_USE");
  }
  return account(site,id);
 }

 @Transactional
 public AccountView update(long site,long id,long expectedVersion,AccountSettings s){
  guard.requireSite(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  // How many administrators there are is not this row's business, so this row's version cannot
  // protect it. Two writers would each read the other as still there and both commit, leaving
  // none. The count and the change are made one step.
  guard.decideAccountsAlone(site);
  var current=account(site,id);
  boolean losesAdmin=current.role()==Role.ADMIN&&(s.parsedRole()!=Role.ADMIN||!s.enabled());
  // Taking away the last way in is not a change anybody means to make, and it cannot be undone
  // from inside: nobody would be left who may hand the authority back.
  if(losesAdmin&&current.enabled()&&otherEnabledAdmins(site,id)==0)
   throw new ConfigurationException(409,"LAST_ADMIN");
  try{
   guard.changed(jdbc.update("UPDATE platform.account SET code=?,display_name=?,role=?,enabled=?,version=version+1"
    +" WHERE site_id=? AND id=? AND version=?",
    s.code(),s.displayName(),s.parsedRole().name(),s.enabled(),site,id,expectedVersion));
  }catch(DuplicateKeyException e){
   throw new ConfigurationException(409,"ACCOUNT_CODE_IN_USE");
  }
  // Authority that was taken away should not survive in a browser that is already logged in.
  if(!s.enabled()||s.parsedRole()!=current.role()) logins.endSessionsOf(id,"ACCOUNT_CHANGED");
  return account(site,id);
 }

 /** Sets a new password and ends the account's live sessions, so the old one stops opening doors. */
 @Transactional
 public AccountView setPassword(long site,long id,String password){
  guard.requireSite(site); account(site,id);
  jdbc.update("UPDATE platform.account SET password_hash=?,version=version+1 WHERE site_id=? AND id=?",
   Passwords.hash(password),site,id);
  logins.endSessionsOf(id,"PASSWORD_CHANGED");
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
 // password_hash is read only as a yes/no: the stored value never leaves this query.
 private static final String SELECT="SELECT id,site_id,code,display_name,role,enabled,"
  +"password_hash IS NOT NULL,version FROM platform.account";
 private static final RowMapper<AccountView> VIEW=(r,n)->new AccountView(
  r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),Role.valueOf(r.getString(5)),
  r.getBoolean(6),r.getBoolean(7),r.getLong(8));
}
