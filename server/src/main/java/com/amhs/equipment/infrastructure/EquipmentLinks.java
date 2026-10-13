package com.amhs.equipment.infrastructure;
import com.amhs.integration.VehicleAdapters;
import com.amhs.integration.mapping.FrameMapper;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.integration.mapping.MessageMapper;
import com.amhs.integration.mapping.SlotCodec;
import com.amhs.integration.mapping.VehicleMessageMapping;
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
 * <p>Nothing is written. Equipment here is a thing this controller takes account of, not one it
 * drives -- and an adapter that could write is an adapter somebody will ask to, long before there
 * is a decision anywhere in this system about what to write.
 */
@Component
public class EquipmentLinks {
 private static final Logger log=LoggerFactory.getLogger(EquipmentLinks.class);
 /** How many missed polls before a unit is called offline, so one slow read is not a fault. */
 private static final int MISSED_POLLS_BEFORE_OFFLINE=3;

 private final JdbcTemplate jdbc; private final MappingLibrary mappings; private final ObjectMapper json;
 private final FrameMapper frames;
 private final Map<Long,Controller> controllers=new ConcurrentHashMap<>();
 private final int timeoutMs,unitId;

 EquipmentLinks(JdbcTemplate jdbc,MappingLibrary mappings,ObjectMapper json,
                @Value("${amhs.device.plc.timeout-ms:1500}") int timeoutMs,
                @Value("${amhs.device.plc.unit-id:1}") int unitId){
  this.jdbc=jdbc; this.mappings=mappings; this.json=json;
  this.frames=new FrameMapper(json,new MessageMapper(json),new SlotCodec(json));
  this.timeoutMs=timeoutMs; this.unitId=unitId;
 }

 /** One unit as its controller holds it. */
 private record Wired(long siteId,long unitId,long profileId,String host,int port,String externalId,
                      String mappingCode){}

 /**
  * Brings the open controllers in line with the configuration, and reads each one that is due.
  *
  * <p>Read every tick rather than remembered, for the same reason the ports are: wiring changes
  * while the server runs, and a link held open to a machine nobody is talking about any more is
  * a state written against a unit that no longer has one.
  */
 @Scheduled(fixedDelayString="${amhs.device.equipment.tick-ms:200}")
 public void poll(){
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
    .filter(m->VehicleAdapters.EQUIPMENT_WORD_V1.equals(m.adapter())).orElse(null);
   if(mapping==null) return;   // another adapter's document, or one nobody could read
   var controller=controllers.computeIfAbsent(profileId,
    id->new Controller(wired.getFirst().host(),wired.getFirst().port(),mapping));
   try{ controller.pollOnce(wired); }
   catch(RuntimeException e){ log.debug("equipment poll {}: {}",profileId,e.getMessage()); }
  });
 }

 /** Every unit in use, by the connection profile that reaches it. */
 private Map<Long,List<Wired>> wiring(){
  var out=new LinkedHashMap<Long,List<Wired>>();
  jdbc.query("SELECT e.site_id,e.unit_id,c.id,c.host,c.port,e.external_unit_id,p.adapter_key"
   +" FROM equipment.communication e"
   +" JOIN integration.connection_profile c ON c.site_id=e.site_id AND c.id=e.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=e.site_id AND p.id=e.protocol_profile_id"
   +" JOIN equipment.unit u ON u.id=e.unit_id AND u.enabled"
   +" WHERE e.enabled AND c.enabled AND c.comm_type='TCP' AND c.role='CLIENT'"
   +" ORDER BY c.id,e.unit_id",(r,n)->{
    var wired=new Wired(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getInt(5),
     r.getString(6),r.getString(7));
    out.computeIfAbsent(wired.profileId(),id->new ArrayList<>()).add(wired);
    return null;
   });
  return out;
 }

 /** One controller: the block it shows, and the units that block speaks for. */
 private final class Controller implements AutoCloseable {
  private final String host; private final int port; private final VehicleMessageMapping mapping;
  private final com.amhs.integration.WordBus wire;
  private final String readAt; private final int words; private final boolean little;
  private volatile long nextPollAt;
  /** When each unit was last actually read, so silence can be told from an unchanged value. */
  private final Map<Long,Long> lastSeen=new ConcurrentHashMap<>();

  Controller(String host,int port,VehicleMessageMapping mapping){
   this.host=host; this.port=port; this.mapping=mapping;
   var area=mapping.area();
   this.readAt=area.read(); this.words=area.words();
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
     if(said.externalVehicleId()!=null&&!said.externalVehicleId().equals(unit.externalId())) continue;
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
