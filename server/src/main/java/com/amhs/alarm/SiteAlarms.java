package com.amhs.alarm;
import com.amhs.alarm.Alarm.Severity;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.integration.CommandOutbox;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.platform.OperationOptions;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.OrderStatus;
import com.amhs.transport.TransportLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is wrong at a site, worked out from the site rather than remembered.
 *
 * <p>Every rule here reads the same state the operating screens read, so an alarm cannot outlive
 * the thing it is about and cannot be missed for having happened while nobody was looking. There is
 * no alarm table, because a table of past events answers a different question than "what is wrong
 * now", and a screen that mixes the two teaches people to scroll past both.
 *
 * <p>A rule earns its place only if the condition is already costing something that can be named.
 * A vehicle reporting an error is not on this list because errors are interesting; it is on it
 * because that vehicle is taking no work and holding wherever it stands. Anything that is merely
 * unusual belongs on the screen it belongs to, not here.
 */
@Service
public class SiteAlarms {
 static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SiteAlarms.class);
 private final FleetState fleet; private final TrafficControl traffic;
 private final TransportLedger ledger; private final CommandOutbox outbox;
 private final OperationOptionConfiguration operations;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 private final com.amhs.transport.RestartCheck restartCheck;

 public SiteAlarms(FleetState fleet,TrafficControl traffic,TransportLedger ledger,
                   CommandOutbox outbox,OperationOptionConfiguration operations,
                   org.springframework.jdbc.core.JdbcTemplate jdbc,com.amhs.transport.RestartCheck restartCheck){
  this.fleet=fleet; this.traffic=traffic; this.ledger=ledger;
  this.outbox=outbox; this.operations=operations; this.jdbc=jdbc; this.restartCheck=restartCheck;
 }

 /** Everything true now, worst first, and within a severity the oldest first. */
 @Transactional(readOnly=true)
 public List<Alarm> alarms(long siteId){
  var options=operations.get(siteId).settings();
  long revisionId=ledger.activeRevision(siteId);
  var out=new ArrayList<Alarm>();
  site(siteId,out);
  vehicles(siteId,revisionId,options,out);
  deadlocks(siteId,out);
  orders(siteId,out);
  commands(siteId,out);
  out.sort(Comparator.comparing(Alarm::severity)
   .thenComparing(a->a.since()==null?OffsetDateTime.MIN:a.since()));
  return withWhoHasLooked(siteId,out);
 }

 /**
  * Attaches, to each live alarm, whether anybody has said they have seen it.
  *
  * <p>An acknowledgement is only ever read here, never applied as a reason to leave an alarm out.
  * Seeing something is not fixing it.
  */
 private List<Alarm> withWhoHasLooked(long siteId,List<Alarm> alarms){
  if(alarms.isEmpty()) return List.of();
  var seen=new java.util.HashMap<String,Alarm.Seen>();
  jdbc.query("SELECT alarm_key,reason,actor_code,acknowledged_at FROM platform.alarm_acknowledgement"
   +" WHERE site_id=?",(r,n)->seen.put(r.getString(1),
    new Alarm.Seen(r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class))),siteId);
  var out=new ArrayList<Alarm>(alarms.size());
  for(Alarm a:alarms) out.add(seen.containsKey(a.key())?a.withSeen(seen.get(a.key())):a);
  return List.copyOf(out);
 }

 /**
  * Records that somebody has looked at an alarm that is true right now.
  *
  * <p>Refused for an alarm that is not live: acknowledging something the site is not saying would
  * put a mark on a condition nobody can see, and leave it waiting for the next time that condition
  * comes back. The alarm stays in the list -- it is still true, and that is the whole point.
  */
 @Transactional
 public Alarm acknowledge(long siteId,String alarmKey,String reason,String actorCode){
  if(reason==null||reason.isBlank())
   throw new IllegalArgumentException("ACKNOWLEDGE_REASON_REQUIRED");
  var live=alarms(siteId).stream().filter(a->a.key().equals(alarmKey)).findFirst()
   .orElseThrow(()->new IllegalArgumentException("ALARM_NOT_LIVE"));
  jdbc.update("INSERT INTO platform.alarm_acknowledgement(site_id,alarm_key,reason,actor_code)"
   +" VALUES(?,?,?,?) ON CONFLICT (site_id,alarm_key) DO UPDATE SET reason=EXCLUDED.reason,"
   +"actor_code=EXCLUDED.actor_code,acknowledged_at=now()",
   siteId,alarmKey,reason.substring(0,Math.min(reason.length(),256)),actorCode);
  // The record of it is written now, with the acknowledgement: the condition may be gone before the
  // upkeep next looks, and the acknowledgement is forgotten with it.
  var acknowledged=alarms(siteId).stream().filter(a->a.key().equals(alarmKey)).findFirst().orElse(live);
  var seen=acknowledged.seen();
  int noted=jdbc.update("UPDATE platform.alarm_occurrence SET ack_reason=?,ack_actor=?,ack_at=? WHERE site_id=? AND alarm_key=?"
   +" AND cleared_at IS NULL",seen==null?reason:seen.reason(),actorCode,seen==null?OffsetDateTime.now():seen.at(),siteId,alarmKey);
  if(noted==0) openOccurrence(siteId,acknowledged);
  return acknowledged;
 }

 /**
  * Forgets acknowledgements of conditions that are no longer true.
  *
  * <p>An acknowledgement belongs to one occurrence. Kept past the end of the condition, it would
  * silence the next one: the same fault returning would arrive already marked as seen by somebody
  * who saw a different one, possibly weeks ago.
  *
  * <p>This runs with the site's sweep rather than when the list is read, because reading must not
  * write -- the list is open to anyone, and a read that changes the database is a read that can be
  * made to change it. The cost is a window: a condition that clears and comes back between two
  * sweeps is still marked seen. The sweep is the site's own heartbeat, so that window is short, and
  * it is the honest trade rather than a hidden one.
  */
 @Transactional
 public int retireAcknowledgementsOfEndedConditions(long siteId){
  var live=alarms(siteId).stream().map(Alarm::key).toList();
  if(live.isEmpty())
   return jdbc.update("DELETE FROM platform.alarm_acknowledgement WHERE site_id=?",siteId);
  var marks=new StringBuilder();
  for(int i=0;i<live.size();i++) marks.append(i==0?"?":",?");
  var args=new Object[live.size()+1];
  args[0]=siteId;
  for(int i=0;i<live.size();i++) args[i+1]=live.get(i);
  return jdbc.update("DELETE FROM platform.alarm_acknowledgement WHERE site_id=?"
   +" AND alarm_key NOT IN ("+marks+")",args);
 }

 /**
  * One stretch of time over which an alarm was seen true: when it was first and last seen, when it
  * was seen gone, and what anybody said about it meanwhile. Observed, not reported: the times are
  * when the upkeep looked, so a condition shorter than its interval can come and go unseen, and that
  * is said rather than hidden.
  */
 public record Occurrence(long id,String key,Severity severity,String rule,String subject,String summary,
                          List<Long> vehicleIds,Long orderId,String commandId,OffsetDateTime conditionSince,
                          OffsetDateTime firstSeenAt,OffsetDateTime lastSeenAt,OffsetDateTime clearedAt,
                          String ackReason,String ackActor,OffsetDateTime ackAt){}

 /**
  * Notes what is true now against what was open: new conditions open an occurrence, ones still
  * true are seen again (with whoever has looked at them), and ones no longer true are closed.
  * Run by the upkeep, never by a read.
  */
 @Transactional
 public void observe(long siteId){
  // When this pass began. Two passes can overlap -- the upkeep and an acknowledgement, or two
  // upkeeps when a read is slow -- and the older one must not close a condition that appeared
  // after it looked. Anything seen since this pass started is somebody else's news, not a
  // condition gone; closing it there would split one alarm into two in the history.
  var startedAt=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
  var live=alarms(siteId);
  var open=new java.util.HashMap<String,Long>();
  jdbc.query("SELECT alarm_key,id FROM platform.alarm_occurrence WHERE site_id=? AND cleared_at IS NULL",
   (org.springframework.jdbc.core.RowCallbackHandler)r->open.put(r.getString(1),r.getLong(2)),siteId);
  var seenNow=new java.util.HashSet<String>();
  for(var a:live){
   seenNow.add(a.key());
   Long id=open.get(a.key());
   if(id==null) openOccurrence(siteId,a);
   else
    jdbc.update("UPDATE platform.alarm_occurrence SET last_seen_at=clock_timestamp(),summary=?,"
     +"ack_reason=COALESCE(?,ack_reason),ack_actor=COALESCE(?,ack_actor),ack_at=COALESCE(?,ack_at) WHERE id=?",
     cut(a.summary(),512),a.seen()==null?null:a.seen().reason(),a.seen()==null?null:a.seen().actorCode(),
     a.seen()==null?null:a.seen().at(),id);
  }
  for(var e:open.entrySet())
   if(!seenNow.contains(e.getKey()))
    jdbc.update("UPDATE platform.alarm_occurrence SET cleared_at=clock_timestamp() WHERE id=? AND last_seen_at<=?",
     e.getValue(),startedAt);
 }
 /** Opens the record of a condition seen true, with whoever has already looked at it. */
 private void openOccurrence(long siteId,Alarm a){
  var about=a.about()==null?Alarm.About.NOTHING:a.about();
  String vehicles=about.vehicleIds().isEmpty()?null
   :"{"+String.join(",",about.vehicleIds().stream().map(String::valueOf).toList())+"}";
  jdbc.update("INSERT INTO platform.alarm_occurrence(site_id,alarm_key,severity,rule,subject,summary,vehicle_ids,order_id,"
   +"command_id,condition_since,ack_reason,ack_actor,ack_at) VALUES(?,?,?,?,?,?,?::bigint[],?,?,?,?,?,?)"
   +" ON CONFLICT (site_id,alarm_key) WHERE cleared_at IS NULL DO NOTHING",
   siteId,a.key(),a.severity().name(),a.rule(),cut(a.subject(),256),cut(a.summary(),512),vehicles,about.orderId(),
   about.commandId(),a.since(),a.seen()==null?null:a.seen().reason(),a.seen()==null?null:a.seen().actorCode(),
   a.seen()==null?null:a.seen().at());
 }
 private static String cut(String s,int n){ return s==null||s.length()<=n?s:s.substring(0,n); }

 /** Occurrences newest first, optionally only those about one vehicle or one order. */
 @Transactional(readOnly=true)
 public List<Occurrence> history(long siteId,Long vehicleId,Long orderId,int limit){
  var sql=new StringBuilder("SELECT id,alarm_key,severity,rule,subject,summary,vehicle_ids,order_id,command_id,condition_since,"
   +"first_seen_at,last_seen_at,cleared_at,ack_reason,ack_actor,ack_at FROM platform.alarm_occurrence WHERE site_id=?");
  var args=new ArrayList<Object>(List.of(siteId));
  if(vehicleId!=null){ sql.append(" AND ?=ANY(vehicle_ids)"); args.add(vehicleId); }
  if(orderId!=null){ sql.append(" AND order_id=?"); args.add(orderId); }
  sql.append(" ORDER BY first_seen_at DESC,id DESC LIMIT ?"); args.add(Math.max(1,Math.min(limit,500)));
  return jdbc.query(sql.toString(),(r,n)->{
   var arr=r.getArray(7);
   var vehicles=new ArrayList<Long>();
   if(arr!=null) for(Object o:(Object[])arr.getArray()) vehicles.add(((Number)o).longValue());
   return new Occurrence(r.getLong(1),r.getString(2),Severity.valueOf(r.getString(3)),r.getString(4),r.getString(5),
    r.getString(6),vehicles,r.getObject(8,Long.class),r.getString(9),r.getObject(10,OffsetDateTime.class),
    r.getObject(11,OffsetDateTime.class),r.getObject(12,OffsetDateTime.class),r.getObject(13,OffsetDateTime.class),
    r.getString(14),r.getString(15),r.getObject(16,OffsetDateTime.class));
  },args.toArray());
 }

 /** How many of each, for a screen that wants the count without the list. */
 @Transactional(readOnly=true)
 public Count count(long siteId){
  var all=alarms(siteId);
  int critical=(int)all.stream().filter(a->a.severity()==Severity.CRITICAL).count();
  int unseen=(int)all.stream().filter(a->!a.acknowledged()).count();
  return new Count(all.size(),critical,all.size()-critical,unseen);
 }
 /**
  * {@code unseen} is how many nobody has said they have looked at. It is counted apart so a badge
  * can stop shouting about a fault somebody is already standing in front of, without pretending
  * the fault is gone.
  */
 public record Count(int total,int critical,int warning,int unseen){}

 private void vehicles(long siteId,long revisionId,OperationOptions options,List<Alarm> out){
  var staleBefore=OffsetDateTime.now().minusSeconds(Math.max(1,options.positionStaleSeconds()));
  for(VehicleSnapshot v:fleet.snapshot(siteId,revisionId)){
   if(!v.enabled()) continue;   // taken out of use on purpose; that is not a fault
   if("ERROR".equals(v.status()))
    out.add(new Alarm("VEHICLE_ERROR:"+v.id(),Severity.CRITICAL,"차량 에러",
     v.code(),"차량이 에러 상태를 보고했습니다.",
     "이 차량은 작업을 받지 않고, 서 있는 자리를 계속 점유합니다. 뒤따르는 차량이 그 자리를 기다립니다.",
     v.reportedAt(),"마지막 보고","fleet").aboutVehicle(v.id()));
   else if(!v.online())
    out.add(new Alarm("VEHICLE_OFFLINE:"+v.id(),Severity.WARNING,"차량 오프라인",
     v.code(),"차량과의 통신이 끊겼습니다.",
     "명령을 보낼 수 없어 배차에서 제외됩니다. 마지막으로 받은 허가 끝에서 멈춥니다.",
     v.reportedAt(),"마지막 보고","fleet").aboutVehicle(v.id()));
   else if(v.reportedAt()!=null&&!v.reportedSince(staleBefore))
    out.add(new Alarm("VEHICLE_STALE:"+v.id(),Severity.WARNING,"위치 보고 지연",
     v.code(),"상태 보고가 %d초 넘게 오지 않았습니다.".formatted(options.positionStaleSeconds()),
     "보고가 오래된 위치로는 이동을 주지 않습니다. 이 차량은 배차와 연장에서 모두 빠집니다.",
     v.reportedAt(),"마지막 보고","fleet").aboutVehicle(v.id()));

   // Only a vehicle that charges has a charge to be low on. A vehicle with charging switched off
   // has no regime this threshold belongs to, and an alarm nobody can act on is the thing that
   // teaches people to stop reading the screen.
   Integer critical=options.chargeCriticalPct();
   boolean charges=v.carriesItsOwnCharge();
   if(charges&&critical!=null&&v.batteryPct()!=null&&v.batteryPct()<critical&&!"CHARGING".equals(v.status()))
    out.add(new Alarm("BATTERY_CRITICAL:"+v.id(),Severity.CRITICAL,"배터리 임계",
     v.code(),"잔량 %.0f%%로 임계값 %d%% 아래입니다.".formatted(v.batteryPct(),critical),
     v.carrierPresent()
      ? "화물을 싣고 있어 충전하러 보내지 않습니다. 하역이 끝나기 전에 멈출 수 있습니다."
      : "작업을 주지 않고 충전을 우선합니다. 비어 있는 충전기가 없으면 그대로 기다립니다.",
     v.reportedAt(),"마지막 보고","fleet").aboutVehicle(v.id()));

   // The other direction, for a vehicle fed from the rail. It has no charging cycle, so its store
   // is a buffer the rail keeps full and a level is not the question -- it is full until it is not
   // being fed. Only falling, for long enough and far enough, is the vehicle not picking its power
   // up. A level would notice far too late, and wherever the vehicle happened to be, which on a
   // single loop is in front of everything else.
   if(!charges&&options.watchesPowerDrop()&&v.socPeakAt()!=null&&v.droppedSincePeak()!=null
      &&v.droppedSincePeak()>=options.powerDropMinPct()
      &&v.socPeakAt().isBefore(OffsetDateTime.now().minusSeconds(options.powerDropWindowSeconds())))
    out.add(new Alarm("POWER_PICKUP:"+v.id(),Severity.CRITICAL,"전력 집전 이상",
     v.code(),"버퍼 잔량이 %d초 동안 오르지 않고 %.0f%% 떨어졌습니다(%.0f%% → %.0f%%).".formatted(
      options.powerDropWindowSeconds(),v.droppedSincePeak(),v.socPeakPct(),v.batteryPct()),
     "레일에서 전력을 받지 못하고 있습니다. 이대로 두면 본선에서 서고, 단일 루프에서는 뒤따르는 차량이 전부 막힙니다."
      +" 서 버리기 전에 정비 구역으로 빼내야 합니다.",
     v.socPeakAt(),"마지막으로 오른 시각","fleet").aboutVehicle(v.id()));
  }
 }

 /** The whole site held: the comparison after a server start is still waiting for somebody. */
 private void site(long siteId,List<Alarm> out){
  var check=restartCheck.state(siteId);
  if(!check.pending()) return;
  long blocking=check.items().stream().filter(com.amhs.transport.RestartCheck.Item::blocking).count();
  out.add(new Alarm("RESTART_CHECK:"+check.checkId(),Severity.CRITICAL,"재시작 대조 대기",
   "사이트","서버가 다시 시작될 때 진행 중이던 작업이 있어, 원장과 차량 상태를 대조해 확인해야 합니다%s.".formatted(
    blocking>0?" (해결되지 않은 항목 %d건)".formatted(blocking):" (확인 가능)"),
   "확인 전까지 사이트 전체가 진행하지 않습니다. 배차, 새 이동 허가, 명령 전송이 모두 멈춰 있습니다.",
   check.since(),"서버 시작 시각","fleet"));
 }

 private void deadlocks(long siteId,List<Alarm> out){
  for(var chain:traffic.deadlocks(siteId)){
   var ids=chain.vehicleIds().stream().map(String::valueOf).toList();
   out.add(new Alarm("DEADLOCK:"+String.join("-",ids),Severity.CRITICAL,"교착",
    "차량 "+String.join(", ",ids),
    "서로가 쥔 자리를 기다리는 닫힌 고리가 생겼습니다.",
    "이 차량들은 스스로 풀리지 않습니다. 누가 양보할지 사람이 정해 주기 전까지 전부 멈춰 있습니다.",
    null,"시작 시각은 기록되지 않습니다","traffic").about(chain.vehicleIds(),null,null));
  }
  for(var recovery:traffic.recoveries(siteId,0)){
   if("DONE".equals(recovery.status())||"ABANDONED".equals(recovery.status())) continue;
   out.add(new Alarm("RECOVERY_OPEN:"+recovery.id(),Severity.WARNING,"양보 복구 진행 중",
    "차량 "+recovery.vehicleId(),
    "교착을 풀기 위해 비켜 서는 중입니다(%s).".formatted(recovery.status()),
    "양보 차량은 상대가 지나갈 때까지 새 예약을 받지 않습니다. 멈추지 않으면 복구가 끝나지 않습니다.",
    recovery.requestedAt(),"요청 시각","traffic").about(java.util.List.of(recovery.vehicleId(),recovery.beneficiaryVehicleId()),null,null));
  }
 }

 private void orders(long siteId,List<Alarm> out){
  // Every one of them: each is a carrier somebody has to find, however old.
  for(var order:ledger.inStatus(siteId,OrderStatus.RECOVERY_REQUIRED)){
    out.add(new Alarm("ORDER_RECOVERY:"+order.id(),Severity.CRITICAL,"작업 복구 대기",
     "작업 #"+order.id()+(order.carrierCode()==null?"":" · "+order.carrierCode()),
     "화물이 어디 있는지 확인되어야 더 진행할 수 있습니다.",
     "이 작업은 스스로 끝나지 않습니다. 화물 위치를 사람이 확인해 기록해야 완료나 취소로 닫힙니다.",
     order.createdAt(),"접수 시각","transport").about(List.of(),order.id(),null));
  }
  // A carrier reported somewhere after its order closed is a carrier nobody else is tracking.
  for(var late:ledger.openLateEvidence(siteId))
   out.add("CONFLICT".equals(late.kind())
    ?new Alarm("LATE_EVIDENCE:"+late.id(),Severity.CRITICAL,"화물 위치 보고 불일치",
     "작업 #"+late.orderId()+" · "+late.orderStatus(),
     "앞선 보고는 %s, 늦게 온 보고는 %s라고 합니다. 어느 것이 나중인지 알 수 없습니다.".formatted(
      late.recordedLocation()==null?"없음":late.recordedLocation(),late.reportedLocation()),
     "화물 위치를 '불명'으로 두었습니다. 차량과 점유는 유지되며, 취소 확인은 복구 대기로 갑니다. 위치를 확인해 기록해야 합니다.",
     late.receivedAt(),"보고 시각","transport").about(List.of(),late.orderId(),late.commandId())
    :new Alarm("LATE_EVIDENCE:"+late.id(),Severity.CRITICAL,"종료된 작업의 늦은 화물 보고",
     "작업 #"+late.orderId()+" · "+late.orderStatus(),
     "작업이 끝난 뒤 화물이 %s에 있다는 보고가 왔습니다(기록: %s).".formatted(late.reportedLocation(),
      late.recordedLocation()==null?"없음":late.recordedLocation()),
     "작업은 다시 열리지 않습니다. 화물 위치를 확인해 재고 기록을 맞추고, 확인 내용을 남겨야 알람이 닫힙니다.",
     late.receivedAt(),"보고 시각","transport").about(List.of(),late.orderId(),late.commandId()));
 }

 private void commands(long siteId,List<Alarm> out){
  var listed=outbox.inDoubt(siteId,200);
  int total=outbox.inDoubtCount(siteId);
  // More than are listed is said as one line with the rest counted, never dropped silently.
  if(total>listed.size())
   out.add(new Alarm("COMMAND_IN_DOUBT:MORE",Severity.WARNING,"명령 결과 미확인 (추가)",
    "외 %d건".formatted(total-listed.size()),"목록에 다 싣지 못한 결과 미확인 명령이 더 있습니다.",
    "진단 화면에서 차례로 정리해야 합니다.",listed.getLast().createdAt(),"가장 늦게 표시된 생성 시각","diagnostics"));
  for(var command:listed)
   out.add(new Alarm("COMMAND_IN_DOUBT:"+command.commandId(),Severity.WARNING,"명령 결과 미확인",
    (command.vehicleId()==null?"":"차량 "+command.vehicleId()+" · ")+command.commandType(),
    "보낸 결과를 확인하지 못했습니다(시도 %d회%s).".formatted(
     command.attempt(),command.lastError()==null?"":", 마지막 오류 "+command.lastError()),
    "이 차량에는 이 명령이 정리되기 전까지 다음 명령이 나가지 않습니다. 연장도 보류됩니다.",
    command.publishedAt()!=null?command.publishedAt():command.createdAt(),
    command.publishedAt()!=null?"발행 시각":"생성 시각","diagnostics")
    .about(command.vehicleId()==null?List.of():List.of(command.vehicleId()),command.orderId(),command.commandId()));
 }
}
