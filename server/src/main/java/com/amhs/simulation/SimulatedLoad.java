package com.amhs.simulation;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleSnapshot;
import com.amhs.platform.OperationOptionConfiguration;
import com.amhs.platform.VehicleType;
import com.amhs.topology.MapSnapshot;
import com.amhs.topology.TopologyQuery;
import com.amhs.traffic.NodeHold;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.OrderRequest;
import com.amhs.transport.OrderStatus;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrder;
import com.amhs.transport.TransportOrders;
import com.amhs.transport.TransportRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A steady stream of work for a simulated site, and a watch kept over it, for running the site under
 * load and saying afterwards what happened.
 *
 * <p>Off unless {@code amhs.simulation.load.per-minute} is set, and only for sites in SIMULATION: it
 * writes orders the way a host would, between two ports one kind of vehicle reaches, never more open
 * at once per kind than that kind has vehicles in automatic work. The choice of ports is seeded
 * ({@code amhs.simulation.load.seed}), so a run can be repeated.
 *
 * <p>Each pass also looks at the floor and counts what must never be seen: two vehicles standing on
 * one node, a vehicle standing on a node it does not hold, and a deadlock. Those counts, the work
 * written and refused, and how long the finished work took are what a run reports.
 */
@Component
public class SimulatedLoad {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SimulatedLoad.class);
 private final OperationOptionConfiguration options; private final TransportOrders orders;
 private final TransportLedger ledger; private final TopologyQuery topology; private final FleetState fleet;
 private final TrafficControl traffic;
 private final double perMinute; private final int perVehicle; private final Random random;
 private final Map<Long,Watch> watches=new ConcurrentHashMap<>();
 private long lastTick=System.nanoTime();

 public SimulatedLoad(OperationOptionConfiguration options,TransportOrders orders,TransportLedger ledger,TopologyQuery topology,
                      FleetState fleet,TrafficControl traffic,
                      @Value("${amhs.simulation.load.per-minute:0}") double perMinute,
                      @Value("${amhs.simulation.load.per-vehicle:1}") int perVehicle,
                      @Value("${amhs.simulation.load.seed:42}") long seed){
  this.options=options; this.orders=orders; this.ledger=ledger; this.topology=topology; this.fleet=fleet;
  this.traffic=traffic; this.perMinute=perMinute; this.perVehicle=Math.max(1,perVehicle);
  this.random=new Random(seed);
 }

 /** What a run has seen so far, from when the load started on this site. */
 public record Report(boolean enabled,double perMinute,int perVehicle,OffsetDateTime startedAt,long written,long refused,long samples,
                      long sameNodeSamples,long unheldNodeSamples,long deadlockSamples,int maxQueued,
                      int completed,int cancelled,int failed,int recovery,Long medianSeconds,Long p95Seconds,int measured){}

 private static final class Watch {
  final OffsetDateTime startedAt=OffsetDateTime.now();
  double due; long written, refused, samples, sameNode, unheld, deadlocks; int maxQueued; long sequence;
 }

 @Scheduled(fixedDelayString="${amhs.simulation.load.tick-ms:1000}")
 void tick(){
  if(perMinute<=0) return;
  long now=System.nanoTime();
  double seconds=Math.min(10.0,(now-lastTick)/1e9);
  lastTick=now;
  for(long siteId:options.simulatedSites()){
   try{ pass(siteId,seconds); }
   catch(RuntimeException e){ log.warn("simulated load skipped site {}: {}",siteId,e.getMessage()); }
  }
 }

 /** One pass for one site: write what is due, then look at the floor. Public so a test can drive it. */
 public synchronized void pass(long siteId,double seconds){
  var w=watches.computeIfAbsent(siteId,k->new Watch());
  long revisionId=ledger.activeRevision(siteId);
  var vehicles=fleet.snapshot(siteId,revisionId);
  if(perMinute>0&&options.progressing(siteId)){
   w.due+=perMinute*seconds/60.0;
   while(w.due>=1){ w.due-=1; write(siteId,revisionId,vehicles,w); }
  }
  look(siteId,vehicles,w);
 }

 private void write(long siteId,long revisionId,List<VehicleSnapshot> vehicles,Watch w){
  var map=topology.getMap(siteId,revisionId);
  var working=new EnumMap<VehicleType,Integer>(VehicleType.class);
  for(var v:vehicles) if(v.enabled()&&v.assignmentEnabled()&&!v.outOfService()) working.merge(v.vType(),1,Integer::sum);
  var open=new EnumMap<VehicleType,Integer>(VehicleType.class);
  for(TransportOrder o:orders.listActive(siteId,500)) open.merge(o.vehicleType(),1,Integer::sum);
  w.maxQueued=Math.max(w.maxQueued,open.values().stream().mapToInt(Integer::intValue).sum());
  // How much may be open at once per kind. One per vehicle keeps every vehicle busy and the queue
  // empty, which is the steady state a plant hopes for; more than one is the state it actually
  // meets at a shift change, where work arrives faster than it can be carried and the queue is
  // what decides who goes first.
  var kinds=new ArrayList<VehicleType>();
  for(var e:working.entrySet())
   if(open.getOrDefault(e.getKey(),0)<e.getValue()*perVehicle) kinds.add(e.getKey());
  if(kinds.isEmpty()) return;
  var kind=kinds.get(random.nextInt(kinds.size()));
  var ports=map.ports().stream().filter(p->p.accessVehicleType()==kind).map(MapSnapshot.Port::code).sorted().toList();
  if(ports.size()<2) return;
  String from=ports.get(random.nextInt(ports.size())), to;
  do{ to=ports.get(random.nextInt(ports.size())); }while(to.equals(from));
  String key="load-"+siteId+"-"+w.startedAt.toEpochSecond()+"-"+(++w.sequence);
  try{
   orders.accept(siteId,new OrderRequest("SIM_LOAD",key,from,to,"LOAD-"+w.sequence,50),"simulated-load",null);
   w.written++;
  }catch(TransportRejected e){ w.refused++; }
 }

 private void look(long siteId,List<VehicleSnapshot> vehicles,Watch w){
  w.samples++;
  var standing=new HashMap<Long,Integer>();
  for(var v:vehicles) if(v.nodeId()!=null&&!v.betweenNodes()) standing.merge(v.nodeId(),1,Integer::sum);
  if(standing.values().stream().anyMatch(n->n>1)) w.sameNode++;
  var held=new HashMap<Long,Set<Long>>();
  for(NodeHold h:traffic.held(siteId)) held.computeIfAbsent(h.vehicleId(),k->new HashSet<>()).add(h.nodeId());
  for(var v:vehicles){
   var mine=held.get(v.id());
   if(v.nodeId()!=null&&mine!=null&&!mine.isEmpty()&&!mine.contains(v.nodeId())){ w.unheld++; break; }
  }
  if(!traffic.deadlocks(siteId).isEmpty()) w.deadlocks++;
 }

 public Report report(long siteId){
  var w=watches.get(siteId);
  if(w==null) return new Report(perMinute>0,perMinute,perVehicle,null,0,0,0,0,0,0,0,0,0,0,0,null,null,0);
  int completed=0,cancelled=0,failed=0;
  var durations=new ArrayList<Long>();
  for(var o:ledger.finishedSince(siteId,w.startedAt)){
   if(!"SIM_LOAD".equals(o.requestSource())) continue;
   switch(o.status()){
    case COMPLETED -> {
     completed++;
     if(o.createdAt()!=null&&o.terminalAt()!=null) durations.add(Duration.between(o.createdAt(),o.terminalAt()).toSeconds());
    }
    case CANCELLED -> cancelled++;
    case FAILED -> failed++;
    default -> { }
   }
  }
  int recovery=(int)ledger.inStatus(siteId,OrderStatus.RECOVERY_REQUIRED).stream()
   .filter(o->"SIM_LOAD".equals(o.requestSource())).count();
  Collections.sort(durations);
  Long median=durations.isEmpty()?null:durations.get((durations.size()-1)/2);
  // Nearest rank: the smallest value with at least 95% of the measured at or below it.
  Long p95=durations.isEmpty()?null:durations.get((int)Math.ceil(0.95*durations.size())-1);
  return new Report(perMinute>0,perMinute,perVehicle,w.startedAt,w.written,w.refused,w.samples,w.sameNode,w.unheld,w.deadlocks,
   w.maxQueued,completed,cancelled,failed,recovery,median,p95,durations.size());
 }
}
