package com.amhs.diagnostics;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.transport.TransportLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What can be said about the machine the server runs on, its network, and each vehicle's link, with
 * every value saying where it came from and how well it is known.
 *
 * <p>A value is KNOWN when it was read just now or within its validity, STALE when the last reading
 * is older than that, UNKNOWN when it could not be read, and UNSUPPORTED when nothing here can read
 * it at all (a link speed, an access point's signal, a broker the site does not run). None of the
 * last three is ever shown as a zero or as healthy: a screen that turns "do not know" into "fine" is
 * how a fault goes unseen.
 *
 * <p>Only the server's own host is read. The browser's machine, other operating PCs and the access
 * points themselves need an agent or the device's own interface, which this does not have; an
 * access point is only asked whether it answers, if somebody names it in
 * {@code amhs.diagnostics.reach} ({@code name=address,...}).
 */
@Service
public class HostDiagnostics {
 private final FleetState fleet; private final TransportLedger ledger; private final CommandOutbox outbox;
 private final OperationOptionConfiguration operations;
 private final String hostId; private final Map<String,String> reachTargets;
 private final Map<String,Reach> reached=new ConcurrentHashMap<>();
 private static final Duration REACH_VALID=Duration.ofSeconds(30);

 public HostDiagnostics(FleetState fleet,TransportLedger ledger,CommandOutbox outbox,OperationOptionConfiguration operations,
                        @Value("${amhs.diagnostics.host-id:}") String hostId,
                        @Value("${amhs.diagnostics.reach:}") String reach){
  this.fleet=fleet; this.ledger=ledger; this.outbox=outbox; this.operations=operations;
  this.hostId=hostId.isBlank()?localName():hostId.trim();
  var targets=new LinkedHashMap<String,String>();
  for(String part:reach.split(",")){
   int eq=part.indexOf('=');
   if(eq>0) targets.put(part.substring(0,eq).trim(),part.substring(eq+1).trim());
  }
  this.reachTargets=Collections.unmodifiableMap(targets);
 }

 public enum State{ KNOWN,STALE,UNKNOWN,UNSUPPORTED }
 /** One value: what it is, in what unit, how well it is known, read by what and when. */
 public record Metric(String name,Double value,String unit,State state,String source,OffsetDateTime measuredAt,String note){}
 public record Nic(String name,String displayName,State state,Boolean up,boolean loopback,boolean virtual,Integer mtu,
                   List<String> addresses,Metric speed,Metric errors){}
 public record Reach(String name,String address,State state,Long millis,OffsetDateTime measuredAt,String note){}
 public record VehicleLink(long vehicleId,String code,State state,boolean online,String source,OffsetDateTime lastReport,
                           Long ageSeconds,int unsettledCommands){}
 public record Report(String hostId,OffsetDateTime at,List<Metric> host,List<Nic> nics,List<Reach> accessPoints,
                      Metric broker,List<VehicleLink> vehicles){}

 private static String localName(){
  try{ return InetAddress.getLocalHost().getHostName(); }catch(Exception e){ return "unknown-host"; }
 }

 public Report report(long siteId){
  var now=OffsetDateTime.now();
  return new Report(hostId,now,host(now),nics(now),accessPoints(now),
   new Metric("broker",null,null,State.UNSUPPORTED,"integration",now,"MQTT 어댑터가 아직 없어 연결 상태를 읽을 수 없습니다"),
   vehicles(siteId,now));
 }

 private List<Metric> host(OffsetDateTime now){
  var out=new ArrayList<Metric>();
  var os=ManagementFactory.getOperatingSystemMXBean();
  if(os instanceof com.sun.management.OperatingSystemMXBean sun){
   out.add(load("cpu.system",sun.getCpuLoad(),now));
   out.add(load("cpu.process",sun.getProcessCpuLoad(),now));
   out.add(bytes("memory.total",sun.getTotalMemorySize(),now));
   out.add(bytes("memory.free",sun.getFreeMemorySize(),now));
  }else{
   for(String n:List.of("cpu.system","cpu.process","memory.total","memory.free"))
    out.add(new Metric(n,null,null,State.UNSUPPORTED,"jvm",now,"이 JVM은 운영체제 값을 주지 않습니다"));
  }
  var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
  out.add(bytes("jvm.heap.used",heap.getUsed(),now));
  out.add(heap.getMax()<0?new Metric("jvm.heap.max",null,"bytes",State.UNKNOWN,"jvm",now,"상한이 정해지지 않았습니다")
   :bytes("jvm.heap.max",heap.getMax(),now));
  out.add(new Metric("jvm.threads",(double)ManagementFactory.getThreadMXBean().getThreadCount(),"count",State.KNOWN,"jvm",now,null));
  out.add(new Metric("jvm.uptime",ManagementFactory.getRuntimeMXBean().getUptime()/1000.0,"s",State.KNOWN,"jvm",now,null));
  File root=new File(".").getAbsoluteFile();
  long total=root.getTotalSpace(), usable=root.getUsableSpace();
  out.add(total<=0?new Metric("disk.total",null,"bytes",State.UNKNOWN,"filesystem",now,"디스크 크기를 읽지 못했습니다")
   :bytes("disk.total",total,now));
  out.add(total<=0?new Metric("disk.usable",null,"bytes",State.UNKNOWN,"filesystem",now,null):bytes("disk.usable",usable,now));
  return out;
 }
 /** A load the JVM reports as negative is one it could not read: unknown, not zero. */
 private static Metric load(String name,double v,OffsetDateTime now){
  return v<0||Double.isNaN(v)?new Metric(name,null,"%",State.UNKNOWN,"jvm",now,"아직 측정값이 없습니다")
   :new Metric(name,Math.round(v*1000)/10.0,"%",State.KNOWN,"jvm",now,null);
 }
 private static Metric bytes(String name,long v,OffsetDateTime now){
  return v<0?new Metric(name,null,"bytes",State.UNKNOWN,"jvm",now,null):new Metric(name,(double)v,"bytes",State.KNOWN,"jvm",now,null);
 }

 private List<Nic> nics(OffsetDateTime now){
  var out=new ArrayList<Nic>();
  try{
   var list=NetworkInterface.networkInterfaces().toList();
   for(var n:list){
    var addresses=n.inetAddresses().map(InetAddress::getHostAddress).toList();
    // Interfaces with no address and no link are the operating system's own plumbing; listing them
    // all would bury the cards that matter.
    if(addresses.isEmpty()&&!safeUp(n)) continue;
    Integer mtu; Boolean up;
    try{ mtu=n.getMTU(); up=n.isUp(); }catch(Exception e){ mtu=null; up=null; }
    out.add(new Nic(n.getName(),n.getDisplayName(),up==null?State.UNKNOWN:State.KNOWN,up,safeLoopback(n),n.isVirtual(),mtu,addresses,
     new Metric("link.speed",null,"Mbps",State.UNSUPPORTED,"jvm",now,"JVM은 링크 속도를 주지 않습니다"),
     new Metric("link.errors",null,"count",State.UNSUPPORTED,"jvm",now,"오류 수는 운영체제·장비 조회가 필요합니다")));
   }
  }catch(Exception e){
   out.add(new Nic("?","네트워크 인터페이스를 읽지 못했습니다",State.UNKNOWN,null,false,false,null,List.of(),
    new Metric("link.speed",null,"Mbps",State.UNKNOWN,"jvm",now,e.getMessage()),
    new Metric("link.errors",null,"count",State.UNKNOWN,"jvm",now,e.getMessage())));
  }
  return out;
 }
 private static boolean safeUp(NetworkInterface n){ try{ return n.isUp(); }catch(Exception e){ return false; } }
 private static boolean safeLoopback(NetworkInterface n){ try{ return n.isLoopback(); }catch(Exception e){ return false; } }

 /** Asked in the background: whether each named access point answers, and how fast. */
 @Scheduled(fixedDelayString="${amhs.diagnostics.reach-ms:10000}")
 public void probeAccessPoints(){
  for(var e:reachTargets.entrySet()){
   var at=OffsetDateTime.now();
   try{
    long t0=System.nanoTime();
    boolean ok=InetAddress.getByName(e.getValue()).isReachable(1500);
    long ms=(System.nanoTime()-t0)/1_000_000;
    reached.put(e.getKey(),ok?new Reach(e.getKey(),e.getValue(),State.KNOWN,ms,at,null)
     :new Reach(e.getKey(),e.getValue(),State.KNOWN,null,at,"응답 없음"));
   }catch(Exception ex){
    reached.put(e.getKey(),new Reach(e.getKey(),e.getValue(),State.UNKNOWN,null,at,ex.getClass().getSimpleName()));
   }
  }
 }
 private List<Reach> accessPoints(OffsetDateTime now){
  var out=new ArrayList<Reach>();
  for(var e:reachTargets.entrySet()){
   var r=reached.get(e.getKey());
   if(r==null) out.add(new Reach(e.getKey(),e.getValue(),State.UNKNOWN,null,null,"아직 확인하지 않았습니다"));
   else if(r.measuredAt().isBefore(now.minus(REACH_VALID)))
    out.add(new Reach(r.name(),r.address(),State.STALE,r.millis(),r.measuredAt(),"마지막 확인이 오래됐습니다"));
   else out.add(r);
  }
  return out;
 }

 private List<VehicleLink> vehicles(long siteId,OffsetDateTime now){
  var out=new ArrayList<VehicleLink>();
  var revision=ledger.activeRevisionIfAny(siteId);
  if(revision.isEmpty()) return out;
  int staleSeconds=operations.get(siteId).settings().positionStaleSeconds();
  for(var v:fleet.snapshot(siteId,revision.getAsLong())){
   if(!v.enabled()) continue;
   var last=v.reportedAt();
   boolean never=last==null||"INITIAL_CONFIGURATION".equals(v.source());
   Long age=never?null:Duration.between(last,now).toSeconds();
   State state=never?State.UNKNOWN:age>staleSeconds?State.STALE:State.KNOWN;
   out.add(new VehicleLink(v.id(),v.code(),state,v.online(),v.source(),never?null:last,age,
    outbox.unresolvedForVehicle(siteId,v.id()).size()));
  }
  return out;
 }
}
