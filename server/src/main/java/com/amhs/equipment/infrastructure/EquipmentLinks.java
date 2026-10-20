package com.amhs.equipment.infrastructure;
import com.amhs.integration.DeviceAdapters;
import com.amhs.integration.mapping.FrameMapper;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.integration.mapping.MessageMapper;
import com.amhs.integration.mapping.SlotCodec;
import com.amhs.integration.mapping.DeviceMessageMapping;
import com.amhs.platform.OperationOptionConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the equipment itself says, read from the controller that holds it.
 *
 * <p>Until now a stocker had a state only in simulation. A site in OPERATION knew nothing about its
 * equipment at all: transport asked whether a unit would take a carrier, got no answer, and carried
 * on as though the answer were yes. A carrier sent to a stocker that is in fault is a carrier
 * somebody fetches by hand.
 *
 * <p>Equipment is read, not corresponded with. A block of words is polled and the difference is the
 * news, so two things follow and both are in the contract rather than in the hope:
 *
 * <ul>
 * <li>How fresh a state can be is the poll interval and nothing better.</li>
 * <li><b>Silence is not "unchanged".</b> A controller that has gone on holding its last values
 *     would otherwise read as READY for ever. When nothing has been read for longer than a few
 *     polls the unit is marked offline, and offline is what the rest of the system acts on.</li>
 * </ul>
 *
 * <p>It also says one thing, where a document asks it to. This controller knows something the
 * machine does not -- that a vehicle has been sent to one of its ports -- and a stocker that is
 * told a minute early can hold the port and have the carrier ready. That statement is all it
 * writes: the handover handshake itself is the vehicle's and the port's own business over their own
 * wires, and this is not in the middle of it. See {@link PortIntents}.
 *
 * <p>Both halves run on <b>this component's own thread</b>. A read waits on a controller that may
 * be gone and a statement waits for a controller to acknowledge it; on the shared scheduler either
 * one would hold up the vehicle links and the simulation tick, which is a stocker's reply stopping
 * vehicles from moving.
 */
@Component
public class EquipmentLinks {
 private static final Logger log=LoggerFactory.getLogger(EquipmentLinks.class);
 /** How many missed polls before a unit is called offline, so one slow read is not a fault. */
 private static final int MISSED_POLLS_BEFORE_OFFLINE=3;

 /** The one statement an equipment document may be asked to carry. */
 private static final String INTENT="PORT_INTENT";

 private final JdbcTemplate jdbc; private final MappingLibrary mappings; private final ObjectMapper json;
 private final FrameMapper frames; private final SlotCodec codec; private final MessageMapper rules;
 private final PortIntents intents; private final OperationOptionConfiguration options;
 private final com.amhs.platform.ControlTiming timing;
 private final Map<Long,Controller> controllers=new ConcurrentHashMap<>();
 private final int timeoutMs,unitId; private final long refreshMs,tickMs;
 /**
  * The thread that waits on controllers.
  *
  * <p>Its own, and for the reason the notice sender has its own: every {@code @Scheduled} method
  * here shares one scheduler thread, and both halves of this component wait on a machine that may
  * not answer. A stocker that has gone quiet must not be able to stop vehicles from moving.
  */
 private final java.util.concurrent.ExecutorService worker=
  java.util.concurrent.Executors.newSingleThreadExecutor(r->{
   var thread=new Thread(r,"equipment-links");
   thread.setDaemon(true);
   return thread;
  });
 /** Whether a pass is already running, so a slow one is not piled on by the next tick. */
 private final java.util.concurrent.atomic.AtomicBoolean passing=new java.util.concurrent.atomic.AtomicBoolean();

 EquipmentLinks(JdbcTemplate jdbc,MappingLibrary mappings,ObjectMapper json,PortIntents intents,
                OperationOptionConfiguration options,com.amhs.platform.ControlTiming timing,
                @Value("${amhs.device.equipment.tick-ms:200}") long tickMs,
                @Value("${amhs.device.plc.timeout-ms:1500}") int timeoutMs,
                @Value("${amhs.device.plc.unit-id:1}") int unitId,
                @Value("${amhs.device.equipment.refresh-ms:30000}") long refreshMs){
  this.jdbc=jdbc; this.mappings=mappings; this.json=json;
  this.rules=new MessageMapper(json); this.codec=new SlotCodec(json);
  this.frames=new FrameMapper(json,rules,codec);
  this.intents=intents; this.options=options; this.timing=timing; this.tickMs=tickMs;
  this.timeoutMs=timeoutMs; this.unitId=unitId; this.refreshMs=refreshMs;
 }

 /**
  * Hands the pass to the worker and returns at once.
  *
  * <p>A tick arriving while the last pass is still going is dropped rather than queued: the
  * wiring is in the database and the next tick will find it.
  */
 void tick(){
  if(!passing.compareAndSet(false,true)) return;
  try{
   worker.execute(()->{
    try(var pass=timing.begin("equipment-links",tickMs)){ poll(); }
    catch(RuntimeException e){ log.error("equipment pass failed",e); }
    finally{ passing.set(false); }
   });
  }catch(RuntimeException e){ passing.set(false); throw e; }
 }
 @PreDestroy void stopWorker(){ worker.shutdownNow(); }

 /** One unit as its controller holds it. */
 private record Wired(long siteId,long unitId,long profileId,String host,int port,String externalId,
                      String mappingCode){}

 /**
  * Brings the open controllers in line with the configuration, reads each one that is due, and
  * tells each the statement its ports are owed.
  *
  * <p>Read every tick rather than remembered, for the same reason the ports are: wiring changes
  * while the server runs, and a link held open to a machine nobody is talking about any more is
  * a state written against a unit that no longer has one.
  *
  * <p>Synchronized, and public so a test can step it and see the result before it returns: two
  * passes at once would have two threads writing the same block, which is half of one statement
  * and half of another.
  */
 public synchronized void poll(){
  Map<Long,List<Wired>> byProfile;
  try{ byProfile=wiring(); }
  catch(RuntimeException e){ log.warn("could not read equipment wiring: {}",e.getMessage()); return; }

  controllers.entrySet().removeIf(open->{
   var want=byProfile.get(open.getKey());
   if(want!=null&&!want.isEmpty()&&open.getValue().reaches(want.getFirst().host(),want.getFirst().port())) return false;
   open.getValue().close();
   return true;
  });
  byProfile.forEach((profileId,wired)->{
   var mapping=mappings.byCode(wired.getFirst().siteId(),wired.getFirst().mappingCode())
    .filter(m->DeviceAdapters.EQUIPMENT_WORD_V1.equals(m.adapter())).orElse(null);
   if(mapping==null) return;   // another adapter's document, or one nobody could read
   var controller=controllers.computeIfAbsent(profileId,
    id->new Controller(wired.getFirst().host(),wired.getFirst().port(),mapping));
   try{ controller.pollOnce(wired); }
   catch(RuntimeException e){ log.debug("equipment poll {}: {}",profileId,e.getMessage()); }
   // Only a site that is really running tells a machine anything. In SIMULATION the vehicle on its
   // way to that port is a vehicle that does not exist, and a real stocker holding a port for it
   // would be holding it for nothing.
   try{ if(running(wired.getFirst().siteId())) controller.tellOnce(wired); }
   catch(RuntimeException e){ log.warn("equipment statement {}: {}",profileId,e.getMessage()); }
  });
 }

 /** Whether this site's equipment is real equipment, as far as this controller is concerned. */
 private boolean running(long siteId){
  try{ return !options.get(siteId).settings().simulated(); }
  catch(RuntimeException e){ return false; }
 }

 /** Every unit in use, by the connection profile that reaches it. */
 private Map<Long,List<Wired>> wiring(){
  var out=new LinkedHashMap<Long,List<Wired>>();
  jdbc.query("SELECT e.site_id,e.unit_id,c.id,c.host,c.port,e.external_unit_id,p.adapter_key,c.tls"
   +" FROM equipment.communication e"
   +" JOIN integration.connection_profile c ON c.site_id=e.site_id AND c.id=e.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=e.site_id AND p.id=e.protocol_profile_id"
   +" JOIN equipment.unit u ON u.id=e.unit_id AND u.enabled"
   +" WHERE e.enabled AND c.enabled AND c.comm_type='TCP' AND c.role='CLIENT'"
   +" ORDER BY c.id,e.unit_id",(r,n)->{
    var wired=new Wired(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getInt(5),
     r.getString(6),r.getString(7));
    // Modbus and MELSEC have no TLS and no authentication of any kind: the protocols predate the
    // question. A profile that asks for TLS and is used for a word block is therefore left alone
    // rather than opened in plaintext -- the whole point of the setting is that nobody has to
    // wonder which of the two happened. Where a machine like this has to be protected, it is
    // protected by what stands in front of it; docs/link-security.md says what that means.
    if(r.getBoolean(8)){
     log.error("equipment unit {} is on a profile that asks for TLS, which a word block cannot speak;"
      +" not connecting (see docs/link-security.md)",wired.unitId());
     return null;
    }
    out.computeIfAbsent(wired.profileId(),id->new ArrayList<>()).add(wired);
    return null;
   });
  return out;
 }

 /** One controller: the block it shows, and the units that block speaks for. */
 private final class Controller implements AutoCloseable {
  private final String host; private final int port; private final DeviceMessageMapping mapping;
  private final com.amhs.integration.WordBus wire;
  private final String readAt,writeAt; private final int words; private final boolean little;
  private volatile long nextPollAt;
  /** When each unit was last actually read, so silence can be told from an unchanged value. */
  private final Map<Long,Long> lastSeen=new ConcurrentHashMap<>();

  Controller(String host,int port,DeviceMessageMapping mapping){
   this.host=host; this.port=port; this.mapping=mapping;
   var area=mapping.area();
   this.readAt=area.read(); this.writeAt=area.write(); this.words=area.words();
   this.little="little".equalsIgnoreCase(area.endian());
   this.wire=com.amhs.integration.WordBuses.open(area.driver(),host,port,unitId,timeoutMs);
  }
  boolean reaches(String otherHost,int otherPort){ return host.equals(otherHost)&&port==otherPort; }

  void pollOnce(List<Wired> units){
   if(System.currentTimeMillis()<nextPollAt) return;
   long interval=Math.max(50,mapping.area().pollMs());
   nextPollAt=System.currentTimeMillis()+interval;
   Map<String,JsonNode> slots=readBlock();
   long now=System.currentTimeMillis();
   if(slots!=null){
    var said=frames.read(mapping,slots).orElse(null);
    if(said!=null) for(var unit:units){
     // A block that names which unit it is about speaks for that one only. One that names none
     // sits in front of a single machine, and the reading is that machine's.
     if(said.externalId()!=null&&!said.externalId().equals(unit.externalId())) continue;
     write(unit,said.values());
     lastSeen.put(unit.unitId(),now);
    }
   }
   // Whatever was read or not, a unit nobody has heard from for several polls is offline, and
   // that is the state to act on rather than the values it last happened to hold.
   long silentFor=interval*MISSED_POLLS_BEFORE_OFFLINE;
   for(var unit:units){
    var seen=lastSeen.get(unit.unitId());
    if(seen==null||now-seen>silentFor) markOffline(unit);
   }
  }

  /**
   * Says what each of this controller's ports is owed, one statement at a time.
   *
   * <p>One block, so one statement at a time: the port's own name is in it, which is how the same
   * registers speak for several ports -- exactly the way the read block names which unit it is
   * about. Each statement is acknowledged before the next is written, because a block overwritten
   * mid-handshake is two statements the machine can read as one.
   */
  void tellOnce(List<Wired> units){
   var out=mapping.outbound().get(INTENT);
   if(out==null||out.slots().isEmpty()) return;    // a document with nothing to say
   var hand=mapping.handshake();
   if(hand==null||writeAt==null) return;           // refused at validation; belt and braces
   for(var unit:units)
    for(var due:intents.dueFor(unit.siteId(),unit.unitId(),refreshMs)) tell(unit,out,hand,due);
  }

  /** One statement: the values, then the request bit, then whatever the controller says about it. */
  private void tell(Wired unit,DeviceMessageMapping.Outbound out,DeviceMessageMapping.Handshake hand,
                    PortIntents.Due due){
   var buffer=new byte[words*2];
   try{
    frames.write(mapping,out.slots(),view(unit,due),buffer,little);
   }catch(SlotCodec.SlotRejected e){
    // Nothing has been written: the statement does not fit the layout the document gave it, which
    // is the document to fix. Said once per statement rather than every pass.
    log.warn("statement for port {} does not fit the layout: {}",due.intent().portCode(),e.getMessage());
    intents.told(due.intent(),due.statement(),due.sequence(),"NOT_TOLD","does not fit the layout: "+e.getMessage());
    return;
   }
   try{ wire.open(); }
   catch(IOException e){
    intents.told(due.intent(),due.statement(),due.sequence(),"NOT_TOLD","not connected");
    return;
   }
   try{
    // The values first with the request bit clear, then the bit. A controller reading between the
    // two sees a statement nobody has asked it to take, which is the one order that is always safe.
    wire.write(writeAt,words(buffer,hand,false));
    wire.write(writeAt,words(buffer,hand,true));
   }catch(com.amhs.integration.WordBus.ControllerRefused e){
    intents.told(due.intent(),due.statement(),due.sequence(),"NOT_TOLD","controller refused "+e.code());
    return;
   }catch(IOException e){
    // Some of it may be in the registers, where it will stay until somebody looks. Said again next
    // pass, which is safe here in the way it would not be for a movement command: the statement has
    // not changed, so saying it twice says the same thing.
    wire.close();
    intents.told(due.intent(),due.statement(),due.sequence(),"IN_DOUBT","write failed: "+e.getMessage());
    return;
   }
   boolean taken=awaitAcceptance(hand,due.sequence());
   try{ if(hand.clear()!=null) wire.write(writeAt,words(buffer,hand,false)); }
   catch(IOException e){ log.warn("request bit on {}:{} could not be cleared: {}",host,port,e.getMessage()); }
   intents.told(due.intent(),due.statement(),due.sequence(),taken?"TOLD":"IN_DOUBT",
    taken?null:"no acknowledgement");
  }

  /** The buffer as registers, with the request bit set or clear. */
  private int[] words(byte[] buffer,DeviceMessageMapping.Handshake hand,boolean request){
   var copy=buffer.clone();
   var bit=mapping.layout().get(hand.thenSet());
   if(bit!=null) codec.write(copy,bit,json.getNodeFactory().booleanNode(request),little);
   var out=new int[words];
   for(int i=0;i<words;i++) out[i]=(copy[i*2]&0xFF)<<8|(copy[i*2+1]&0xFF);
   return out;
  }

  /**
   * Waits for the controller's own bit, with the sequence it echoes saying which statement it took.
   *
   * <p>The echo is not a nicety. Without it, the bit left up from the statement before would read
   * as acceptance of this one, and a port would be recorded as told something nobody ever took.
   */
  private boolean awaitAcceptance(DeviceMessageMapping.Handshake hand,int sequence){
   long until=System.currentTimeMillis()+hand.timeoutMs();
   while(System.currentTimeMillis()<until){
    var slots=readBlock();
    if(slots!=null){
     var accepted=slots.get(hand.acceptedBit());
     var echo=slots.get(hand.echoSlot());
     if(accepted!=null&&accepted.asBoolean()&&echo!=null&&echo.asInt()==sequence) return true;
    }
    try{ Thread.sleep(25); }catch(InterruptedException e){ Thread.currentThread().interrupt(); return false; }
   }
   return false;
  }

  /** The statement as a mapping document reads it: the unit's own name, and what is true there. */
  private MessageMapper.Command view(Wired unit,PortIntents.Due due){
   return new MessageMapper.Command("intent-"+due.intent().portId()+"-"+due.sequence(),INTENT,null,
    unit.unitId(),unit.externalId(),due.sequence(),java.time.OffsetDateTime.now().toString(),
    intents.payload(due.intent()),Map.of());
  }

  /** The read block as named values, or null when it could not be read. */
  private Map<String,JsonNode> readBlock(){
   try{
    wire.open();
    var registers=wire.read(readAt,words);
    var buffer=new byte[words*2];
    for(int i=0;i<words;i++){ buffer[i*2]=(byte)(registers[i]>>8); buffer[i*2+1]=(byte)registers[i]; }
    return frames.decode(mapping,buffer,little,"read");
   }catch(IOException e){
    wire.close();
    return null;
   }
  }
  @Override public void close(){ wire.close(); }
 }

 /** What the unit said, written where the rest of the system looks for it. */
 private void write(Wired wired,Map<String,JsonNode> values){
  var status=text(values.get("status"));
  if(status!=null&&!STATUSES.contains(status)){
   // A status this system does not know is not forced into one it does. The reading is dropped
   // and said, so a machine reporting nonsense is visible rather than quietly idle.
   log.warn("equipment {} reports status {}, which this controller does not know",wired.externalId(),status);
   status=null;
  }
  jdbc.update("INSERT INTO equipment.device_state(unit_id,available,call_enabled,status,online,last_seen_at,updated_at)"
   +" VALUES(?,?,?,?,TRUE,clock_timestamp(),clock_timestamp())"
   +" ON CONFLICT(unit_id) DO UPDATE SET available=excluded.available,call_enabled=excluded.call_enabled,"
   +"status=excluded.status,online=TRUE,last_seen_at=clock_timestamp(),updated_at=clock_timestamp()",
   wired.unitId(),bool(values.get("available")),bool(values.get("callEnabled")),status);
 }

 /**
  * Says nobody has heard from this unit.
  *
  * <p>What it last said is left exactly as it was. Overwriting it would lose the one thing worth
  * knowing about a machine that has gone quiet -- what it was doing when it did.
  */
 private void markOffline(Wired wired){
  jdbc.update("INSERT INTO equipment.device_state(unit_id,online,updated_at)"
   +" VALUES(?,FALSE,clock_timestamp())"
   +" ON CONFLICT(unit_id) DO UPDATE SET online=FALSE,updated_at=clock_timestamp()"
   +" WHERE equipment.device_state.online",wired.unitId());
 }

 private static final Set<String> STATUSES=
  Set.of("IDLE","READY","BUSY","ERROR","OFFLINE","LOADING","UNLOADING");
 private static String text(JsonNode node){ return node==null||node.isNull()?null:node.asText(); }
 private static Boolean bool(JsonNode node){ return node==null||node.isNull()?null:node.asBoolean(); }

 @PreDestroy void closeAll(){ controllers.values().forEach(Controller::close); controllers.clear(); }
}
