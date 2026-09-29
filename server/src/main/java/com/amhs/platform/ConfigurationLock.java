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
}
