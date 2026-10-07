package com.amhs.statistics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/**
 * How the work of each site day ended, read from the order ledger.
 *
 * <p>A site day is the half-open interval [00:00, next 00:00) in the site's zone, turned into UTC and
 * matched against when an order ended ({@code terminal_at}). Each order ends once, so each is counted
 * once, on the day it ended, however many events led there.
 *
 * <p>Normal-completion rate (policy {@value #POLICY}) = completed / (completed + cancelled after it
 * started + failed). An order cancelled before it ever started is counted apart and is not in the
 * rate: nothing was attempted. Work still under way or waiting for a person is counted apart too.
 * A day with nothing in the denominator has no rate (N/A), not 0% and not 100%.
 *
 * <p>This is a measure of how work ended as the ledger records it. It is not a proof of where any
 * carrier physically is, and it is named so as not to be read as one.
 */
@Service
public class DailyStatistics {
 public static final String POLICY="v1: completed/(completed+cancelledAfterStart+failed)";
 private final JdbcTemplate jdbc; private final ZoneId defaultZone;

 public DailyStatistics(JdbcTemplate jdbc,@Value("${amhs.site.zone:Asia/Seoul}") String zone){
  this.jdbc=jdbc; this.defaultZone=ZoneId.of(zone);
 }

 public record Day(LocalDate date,OffsetDateTime fromUtc,OffsetDateTime toUtc,int completed,int cancelledAfterStart,int failed,
                   int cancelledBeforeStart,Double normalRatePct){}
 public record Reason(String reason,int afterStart,int beforeStart){}
 public record Abnormal(long orderId,String status,boolean started,String cancelReason,OffsetDateTime endedAt){}
 /** Work accepted in the range that has not ended: under way, and waiting for a person. */
 public record Open(int underWay,int waitingForPerson){}
 public record Report(String zone,String policy,List<Day> days,List<Reason> cancelReasons,List<Abnormal> abnormal,int abnormalTotal,Open open){}

 @Transactional(readOnly=true)
 public Report daily(long siteId,LocalDate from,LocalDate to,String zone){
  ZoneId z=zone==null||zone.isBlank()?defaultZone:ZoneId.of(zone);
  if(to.isBefore(from)) throw new IllegalArgumentException("RANGE_REVERSED");
  if(from.plusDays(366).isBefore(to)) throw new IllegalArgumentException("RANGE_TOO_LONG");
  var start=from.atStartOfDay(z).toOffsetDateTime(); var end=to.plusDays(1).atStartOfDay(z).toOffsetDateTime();
  var days=new ArrayList<Day>();
  for(var d=from;!d.isAfter(to);d=d.plusDays(1)){
   var a=d.atStartOfDay(z).toOffsetDateTime(); var b=d.plusDays(1).atStartOfDay(z).toOffsetDateTime();
   var row=jdbc.queryForMap("SELECT count(*) FILTER (WHERE status='COMPLETED') AS completed,"
    +"count(*) FILTER (WHERE status='CANCELLED' AND started_at IS NOT NULL) AS cancelled_after,"
    +"count(*) FILTER (WHERE status='FAILED') AS failed,"
    +"count(*) FILTER (WHERE status='CANCELLED' AND started_at IS NULL) AS cancelled_before"
    +" FROM transport.order WHERE site_id=? AND terminal_at>=? AND terminal_at<?",siteId,a,b);
   int c=((Number)row.get("completed")).intValue(), ca=((Number)row.get("cancelled_after")).intValue(),
    f=((Number)row.get("failed")).intValue(), cb=((Number)row.get("cancelled_before")).intValue();
   int denominator=c+ca+f;
   Double rate=denominator==0?null:Math.round(1000.0*c/denominator)/10.0;
   days.add(new Day(d,a.withOffsetSameInstant(ZoneOffset.UTC),b.withOffsetSameInstant(ZoneOffset.UTC),c,ca,f,cb,rate));
  }
  var reasons=jdbc.query("SELECT COALESCE(NULLIF(trim(cancel_reason),''),'(사유 없음)') AS reason,"
   +"count(*) FILTER (WHERE started_at IS NOT NULL),count(*) FILTER (WHERE started_at IS NULL)"
   +" FROM transport.order WHERE site_id=? AND status='CANCELLED' AND terminal_at>=? AND terminal_at<?"
   +" GROUP BY 1 ORDER BY count(*) DESC,1",(r,n)->new Reason(r.getString(1),r.getInt(2),r.getInt(3)),siteId,start,end);
  int abnormalTotal=jdbc.queryForObject("SELECT count(*) FROM transport.order WHERE site_id=? AND terminal_at>=? AND terminal_at<?"
   +" AND (status='FAILED' OR (status='CANCELLED' AND started_at IS NOT NULL))",Integer.class,siteId,start,end);
  var abnormal=jdbc.query("SELECT id,status,started_at IS NOT NULL,cancel_reason,terminal_at FROM transport.order"
   +" WHERE site_id=? AND terminal_at>=? AND terminal_at<? AND (status='FAILED' OR (status='CANCELLED' AND started_at IS NOT NULL))"
   +" ORDER BY terminal_at DESC,id DESC LIMIT 200",(r,n)->new Abnormal(r.getLong(1),r.getString(2),r.getBoolean(3),r.getString(4),
   r.getObject(5,OffsetDateTime.class)),siteId,start,end);
  var open=jdbc.queryForObject("SELECT count(*) FILTER (WHERE status<>'RECOVERY_REQUIRED'),count(*) FILTER (WHERE status='RECOVERY_REQUIRED')"
   +" FROM transport.order WHERE site_id=? AND created_at>=? AND created_at<? AND status NOT IN ('COMPLETED','CANCELLED','FAILED')",
   (r,n)->new Open(r.getInt(1),r.getInt(2)),siteId,start,end);
  return new Report(z.getId(),POLICY,days,reasons,abnormal,abnormalTotal,open);
 }
}
