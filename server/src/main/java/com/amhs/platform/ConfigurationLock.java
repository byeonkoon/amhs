package com.amhs.platform;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
@Service
public class ConfigurationLock {
 private final JdbcTemplate jdbc;
 public ConfigurationLock(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void acquire(long siteId){
  var states=jdbc.queryForList("SELECT mode FROM platform.site_operation WHERE site_id=? FOR UPDATE",String.class,siteId);
  if(states.isEmpty())throw new ConfigurationException(404,"SITE_NOT_CONFIGURED");
  if(!"MAINTENANCE".equals(states.getFirst()))throw new ConfigurationException(409,"SITE_NOT_IN_MAINTENANCE");
 }
 public void requireSite(long siteId){
  if(jdbc.queryForObject("SELECT COUNT(*) FROM platform.site WHERE id=?",Integer.class,siteId)==0)
   throw new ConfigurationException(404,"SITE_NOT_FOUND");
 }
 public long nextId(){return jdbc.queryForObject("SELECT nextval('platform.configuration_id')",Long.class);}
 public void changed(int count){if(count!=1)throw new ConfigurationException(409,"VERSION_CONFLICT");}
 /**
  * Lets one account decision for a site happen at a time.
  *
  * <p>A rule such as "not the last administrator" is about how many rows there are, not about the
  * row being written. Each writer checks its own row's version, those versions are independent, and
  * so two writers each read that the other administrator is still there and both commit: nobody is
  * left who can hand the authority back. Counting and changing have to be one step, and no row
  * version can make them one.
  *
  * <p>It is taken before anything is read, so the count a decision rests on cannot change under it.
  * Unlike {@link #acquire} it does not ask the site to be in maintenance: shutting a login off is
  * most needed while the site is running.
  */
 @Transactional(propagation=Propagation.MANDATORY)
 public void decideAccountsAlone(long siteId){
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('amhs-accounts'),CAST(? AS INT))",Object.class,(int)siteId);
 }
}
