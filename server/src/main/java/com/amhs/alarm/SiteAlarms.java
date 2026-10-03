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
 private final FleetState fleet; private final TrafficControl traffic;
 private final TransportLedger ledger; private final CommandOutbox outbox;
 private final OperationOptionConfiguration operations;

 public SiteAlarms(FleetState fleet,TrafficControl traffic,TransportLedger ledger,
                   CommandOutbox outbox,OperationOptionConfiguration operations){
  this.fleet=fleet; this.traffic=traffic; this.ledger=ledger;
  this.outbox=outbox; this.operations=operations;
 }

 /** Everything true now, worst first, and within a severity the oldest first. */
 @Transactional(readOnly=true)
 public List<Alarm> alarms(long siteId){
  var options=operations.get(siteId).settings();
  long revisionId=ledger.activeRevision(siteId);
  var out=new ArrayList<Alarm>();
  vehicles(siteId,revisionId,options,out);
  deadlocks(siteId,out);
  orders(siteId,out);
  commands(siteId,out);
  out.sort(Comparator.comparing(Alarm::severity)
   .thenComparing(a->a.since()==null?OffsetDateTime.MIN:a.since()));
  return List.copyOf(out);
 }

 /** How many of each, for a screen that wants the count without the list. */
 @Transactional(readOnly=true)
 public Count count(long siteId){
  var all=alarms(siteId);
  int critical=(int)all.stream().filter(a->a.severity()==Severity.CRITICAL).count();
  return new Count(all.size(),critical,all.size()-critical);
 }
 public record Count(int total,int critical,int warning){}

 private void vehicles(long siteId,long revisionId,OperationOptions options,List<Alarm> out){
  var staleBefore=OffsetDateTime.now().minusSeconds(Math.max(1,options.positionStaleSeconds()));
  for(VehicleSnapshot v:fleet.snapshot(siteId,revisionId)){
   if(!v.enabled()) continue;   // taken out of use on purpose; that is not a fault
   if("ERROR".equals(v.status()))
    out.add(new Alarm("VEHICLE_ERROR:"+v.id(),Severity.CRITICAL,"차량 에러",
     v.code(),"차량이 에러 상태를 보고했습니다.",
     "이 차량은 작업을 받지 않고, 서 있는 자리를 계속 점유합니다. 뒤따르는 차량이 그 자리를 기다립니다.",
     v.reportedAt(),"마지막 보고","fleet"));
   else if(!v.online())
    out.add(new Alarm("VEHICLE_OFFLINE:"+v.id(),Severity.WARNING,"차량 오프라인",
     v.code(),"차량과의 통신이 끊겼습니다.",
     "명령을 보낼 수 없어 배차에서 제외됩니다. 마지막으로 받은 허가 끝에서 멈춥니다.",
     v.reportedAt(),"마지막 보고","fleet"));
   else if(v.reportedAt()!=null&&!v.reportedSince(staleBefore))
    out.add(new Alarm("VEHICLE_STALE:"+v.id(),Severity.WARNING,"위치 보고 지연",
     v.code(),"상태 보고가 %d초 넘게 오지 않았습니다.".formatted(options.positionStaleSeconds()),
     "보고가 오래된 위치로는 이동을 주지 않습니다. 이 차량은 배차와 연장에서 모두 빠집니다.",
     v.reportedAt(),"마지막 보고","fleet"));

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
     v.reportedAt(),"마지막 보고","fleet"));

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
     v.socPeakAt(),"마지막으로 오른 시각","fleet"));
  }
 }

 private void deadlocks(long siteId,List<Alarm> out){
  for(var chain:traffic.deadlocks(siteId)){
   var ids=chain.vehicleIds().stream().map(String::valueOf).toList();
   out.add(new Alarm("DEADLOCK:"+String.join("-",ids),Severity.CRITICAL,"교착",
    "차량 "+String.join(", ",ids),
    "서로가 쥔 자리를 기다리는 닫힌 고리가 생겼습니다.",
    "이 차량들은 스스로 풀리지 않습니다. 누가 양보할지 사람이 정해 주기 전까지 전부 멈춰 있습니다.",
    null,"시작 시각은 기록되지 않습니다","traffic"));
  }
  for(var recovery:traffic.recoveries(siteId,0)){
   if("DONE".equals(recovery.status())||"ABANDONED".equals(recovery.status())) continue;
   out.add(new Alarm("RECOVERY_OPEN:"+recovery.id(),Severity.WARNING,"양보 복구 진행 중",
    "차량 "+recovery.vehicleId(),
    "교착을 풀기 위해 비켜 서는 중입니다(%s).".formatted(recovery.status()),
    "양보 차량은 상대가 지나갈 때까지 새 예약을 받지 않습니다. 멈추지 않으면 복구가 끝나지 않습니다.",
    recovery.requestedAt(),"요청 시각","traffic"));
  }
 }

 private void orders(long siteId,List<Alarm> out){
  for(var order:ledger.listActive(siteId,500)){
   if(order.status()==OrderStatus.RECOVERY_REQUIRED)
    out.add(new Alarm("ORDER_RECOVERY:"+order.id(),Severity.CRITICAL,"작업 복구 대기",
     "작업 #"+order.id()+(order.carrierCode()==null?"":" · "+order.carrierCode()),
     "화물이 어디 있는지 확인되어야 더 진행할 수 있습니다.",
     "이 작업은 스스로 끝나지 않습니다. 화물 위치를 사람이 확인해 기록해야 완료나 취소로 닫힙니다.",
     order.createdAt(),"접수 시각","transport"));
  }
 }

 private void commands(long siteId,List<Alarm> out){
  for(var command:outbox.inDoubt(siteId,50))
   out.add(new Alarm("COMMAND_IN_DOUBT:"+command.commandId(),Severity.WARNING,"명령 결과 미확인",
    (command.vehicleId()==null?"":"차량 "+command.vehicleId()+" · ")+command.commandType(),
    "보낸 결과를 확인하지 못했습니다(시도 %d회%s).".formatted(
     command.attempt(),command.lastError()==null?"":", 마지막 오류 "+command.lastError()),
    "이 차량에는 이 명령이 정리되기 전까지 다음 명령이 나가지 않습니다. 연장도 보류됩니다.",
    command.publishedAt()!=null?command.publishedAt():command.createdAt(),
    command.publishedAt()!=null?"발행 시각":"생성 시각","diagnostics"));
 }
}
