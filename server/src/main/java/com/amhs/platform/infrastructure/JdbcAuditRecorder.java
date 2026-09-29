package com.amhs.platform.infrastructure;
import com.amhs.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Service
class JdbcAuditRecorder implements AuditRecorder {
 private final JdbcTemplate jdbc;
 JdbcAuditRecorder(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /** Written in its own transaction so a rolled-back request still leaves its audit trail. */
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void record(AuditEntry e){
  var actor=e.actor();
  jdbc.update("INSERT INTO platform.audit_event(site_id,actor_account_id,actor_code,actor_role,action,target,result,status_code,reason,trace_id)"
   +" VALUES(?,?,?,?,?,?,?,?,?,?)",
   actor.siteId()==0?null:actor.siteId(),actor.accountId(),actor.code(),actor.role().name(),
   e.action(),trim(e.target(),512),e.result(),e.statusCode(),trim(e.reason(),512),e.traceId());
 }
 private static String trim(String v,int max){return v==null?null:(v.length()<=max?v:v.substring(0,max));}
}
