package com.amhs.integration.infrastructure;
import com.amhs.integration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
@Service
class JdbcCommandOutbox implements CommandOutbox {
 private final JdbcTemplate jdbc;
 JdbcCommandOutbox(JdbcTemplate jdbc){this.jdbc=jdbc;}

 /**
  * Joins the caller's transaction and refuses to run without one: a command written outside the
  * transaction that caused it would survive a rolled back state change and move a vehicle for a
  * state the system no longer believes in.
  */
 @Transactional(propagation=Propagation.MANDATORY)
 public DeviceCommand enqueue(long orderId,String commandId,String commandType,String payload){
  jdbc.update("INSERT INTO integration.command_outbox(order_id,command_id,command_type,payload,status)"
   +" VALUES(?,?,?,?,'PENDING') ON CONFLICT(command_id) DO NOTHING",orderId,commandId,commandType,payload);
  // A caller that retries its own transaction reuses the command id and must get the first row
  // back rather than a second command for the same decision.
  return byCommandId(commandId);
 }

 /**
  * Claimed rows are marked before the send and outside the sending transaction, so an interrupted
  * send is left visible as DISPATCHING instead of quietly returning to the queue.
  */
 @Transactional
 public List<DeviceCommand> claim(int limit,int maxAttempts){
  return jdbc.query("UPDATE integration.command_outbox SET status='DISPATCHING',attempt=attempt+1,last_error=NULL"
   +" WHERE id IN (SELECT id FROM integration.command_outbox"
   +"  WHERE status IN ('PENDING','FAILED') AND available_at<=now() AND attempt<?"
   +"  ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT ?) RETURNING "+COLS,
   MAPPER,maxAttempts,Math.max(limit,1));
 }

 @Transactional
 public DeviceCommand settle(String commandId,DeviceChannel.Verdict verdict,Duration retryDelay){
  long seconds=retryDelay==null?0:Math.max(retryDelay.toSeconds(),0);
  int updated=switch(verdict.outcome()){
   case DELIVERED -> jdbc.update("UPDATE integration.command_outbox SET status='SENT',published_at=now(),"
    +"last_outcome=?,last_error=? WHERE command_id=? AND status='DISPATCHING'",
    verdict.outcome().name(),verdict.detail(),commandId);
   case NOT_DELIVERED -> jdbc.update("UPDATE integration.command_outbox SET status='FAILED',"
    +"available_at=now()+make_interval(secs=>?),last_outcome=?,last_error=? WHERE command_id=? AND status='DISPATCHING'",
    (double)seconds,verdict.outcome().name(),verdict.detail(),commandId);
   // The status is deliberately left alone: nobody can say whether the device has this command,
   // so it stays out of the claimable set until someone decides.
   case IN_DOUBT -> jdbc.update("UPDATE integration.command_outbox SET last_outcome=?,last_error=?"
    +" WHERE command_id=? AND status='DISPATCHING'",verdict.outcome().name(),verdict.detail(),commandId);
  };
  if(updated==0) throw new IllegalStateException("COMMAND_NOT_DISPATCHING");
  return byCommandId(commandId);
 }

 /** Receipt resolves doubt, including for a command a failed send had already written off. */
 @Transactional
 public DeviceCommand acknowledge(String commandId){
  var current=byCommandId(commandId);
  if(current==null) throw new IllegalStateException("COMMAND_NOT_FOUND");
  if(current.status()==CommandStatus.ACCEPTED) return current;
  if(current.status()==CommandStatus.PENDING) throw new IllegalStateException("COMMAND_NOT_SENT");
  jdbc.update("UPDATE integration.command_outbox SET status='ACCEPTED',settled_at=now(),"
   +"published_at=COALESCE(published_at,now()) WHERE command_id=?",commandId);
  return byCommandId(commandId);
 }

 @Transactional(readOnly=true)
 public List<DeviceCommand> byOrder(long siteId,long orderId){
  return jdbc.query("SELECT "+JOINED+" FROM integration.command_outbox c JOIN transport.order o ON o.id=c.order_id"
   +" WHERE o.site_id=? AND c.order_id=? ORDER BY c.id",MAPPER,siteId,orderId);
 }
 @Transactional(readOnly=true)
 public List<DeviceCommand> inDoubt(long siteId,int limit){
  return jdbc.query("SELECT "+JOINED+" FROM integration.command_outbox c JOIN transport.order o ON o.id=c.order_id"
   +" WHERE o.site_id=? AND c.status IN ('DISPATCHING','SENT') ORDER BY c.created_at,c.id LIMIT ?",
   MAPPER,siteId,Math.min(Math.max(limit,1),200));
 }

 private DeviceCommand byCommandId(String commandId){
  var rows=jdbc.query("SELECT "+COLS+" FROM integration.command_outbox WHERE command_id=?",MAPPER,commandId);
  return rows.isEmpty()?null:rows.getFirst();
 }
 // The join queries need every column qualified; deriving them keeps one list.
 private static final String JOINED_PREFIX="c.";
 private static final String COLS="id,order_id,command_id,command_type,payload,status,attempt,"
  +"last_outcome,last_error,created_at,published_at,settled_at";
 private static final String JOINED=JOINED_PREFIX+COLS.replace(",",","+JOINED_PREFIX);
 private static final RowMapper<DeviceCommand> MAPPER=(r,n)->new DeviceCommand(
  r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),
  CommandStatus.valueOf(r.getString(6)),r.getInt(7),r.getString(8),r.getString(9),
  r.getObject(10,OffsetDateTime.class),r.getObject(11,OffsetDateTime.class),r.getObject(12,OffsetDateTime.class));
}
