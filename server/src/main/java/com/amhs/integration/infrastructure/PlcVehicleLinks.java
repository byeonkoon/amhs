package com.amhs.integration.infrastructure;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.amhs.integration.mapping.*;
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
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vehicle link to a controller that is a PLC: a block of registers written, a block read, and a
 * handshake in between.
 *
 * <p>A PLC is not a correspondent. Nothing is sent to this system and nothing arrives: values are
 * written into registers that keep them, and a block is read round and round. Both halves of that
 * change what this adapter has to be careful about.
 *
 * <p><b>Writing.</b> A register keeps what was put in it, so writing a command is not telling
 * anybody anything -- the controller picks it up when it looks. The command is therefore the
 * values, then a request bit, and it counts as taken only when the controller raises its own bit
 * and echoes back the sequence number it took. Without that echo an acknowledgement cannot be told
 * from the one before, and a command repeated into a register that still holds it is a second
 * movement, not a retry. That is why a write with no acknowledgement is IN_DOUBT and is never sent
 * again by this system on its own.
 *
 * <p><b>Reading.</b> The read block is polled, so how fresh a position can be is the poll interval
 * and nothing better. The mapping says the interval; the site's stale-position limit has to be
 * longer than it, or every vehicle reads as stale for part of every cycle.
 */
@Component
class PlcVehicleLinks implements DeviceChannel {
 private static final Logger log=LoggerFactory.getLogger(PlcVehicleLinks.class);
 private final JdbcTemplate jdbc; private final FleetState fleet; private final MappingLibrary mappings;
 private final ObjectMapper json; private final MessageMapper rules; private final FrameMapper frames;
 private final SlotCodec codec;
 private final Map<Long,Controller> controllers=new ConcurrentHashMap<>();
 private final int timeoutMs,unitId;

 PlcVehicleLinks(JdbcTemplate jdbc,FleetState fleet,MappingLibrary mappings,ObjectMapper json,
                 @Value("${amhs.device.plc.timeout-ms:1500}") int timeoutMs,
                 @Value("${amhs.device.plc.unit-id:1}") int unitId){
  this.jdbc=jdbc; this.fleet=fleet; this.mappings=mappings; this.json=json;
  this.rules=new MessageMapper(json); this.codec=new SlotCodec(json);
  this.frames=new FrameMapper(json,rules,codec);
  this.timeoutMs=timeoutMs; this.unitId=unitId;
 }

 private record Wired(long siteId,long profileId,String host,int port,long vehicleId,String mappingCode,String externalId){}

 @Override public Verdict send(DeviceCommand command){
  if(command.vehicleId()==null) return Verdict.notDelivered("COMMAND_NAMES_NO_VEHICLE");
  var wired=wiring(command.siteId(),command.vehicleId());
  if(wired==null) return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  var mapping=mappings.byCode(wired.mappingCode()).orElse(null);
  if(mapping==null) return Verdict.notDelivered("MAPPING_NOT_IN_USE");
  // Another adapter's document on this link is not this adapter's to act on.
  if(!com.amhs.integration.VehicleAdapters.PLC_WORD_V1.equals(mapping.adapter()))
   return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  var out=mapping.outbound().get(command.commandType());
  if(out==null||out.slots().isEmpty()) return Verdict.notDelivered("COMMAND_NOT_IN_MAPPING");
  var controller=controllers.compute(wired.profileId(),(id,open)->
   open!=null&&open.reaches(wired.host(),wired.port())?open:replace(open,wired,mapping));
  return controller.send(wired,mapping,out,command);
 }
 private Controller replace(Controller open,Wired wired,VehicleMessageMapping mapping){
  if(open!=null) open.close();
  return new Controller(wired.host(),wired.port(),mapping);
 }

 /** Every controller is read on its own interval, which the mapping sets. */
 @Scheduled(fixedDelayString="${amhs.device.plc.tick-ms:100}")
 void poll(){
  controllers.forEach((profileId,controller)->{
   try{ controller.pollOnce(); }
   catch(RuntimeException e){ log.debug("plc poll {}: {}",profileId,e.getMessage()); }
  });
 }

 /** One PLC: the registers it is given, the registers it shows, and who is listening. */
 private final class Controller {
  private final String host; private final int port; private final VehicleMessageMapping mapping;
  private final ModbusTcp modbus;
  private final int writeAt,readAt,words; private final boolean little;
  private final Map<Long,Wired> vehicles=new ConcurrentHashMap<>();
  private volatile long nextPollAt;

  Controller(String host,int port,VehicleMessageMapping mapping){
   this.host=host; this.port=port; this.mapping=mapping;
   var area=mapping.area();
   this.writeAt=ModbusTcp.wireAddress(area.write());
   this.readAt=ModbusTcp.wireAddress(area.read());
   this.words=area.words();
   this.little="little".equalsIgnoreCase(area.endian());
   this.modbus=new ModbusTcp(host,port,unitId,timeoutMs);
  }
  boolean reaches(String otherHost,int otherPort){ return host.equals(otherHost)&&port==otherPort; }

  synchronized Verdict send(Wired wired,VehicleMessageMapping mapping,VehicleMessageMapping.Outbound out,
                            DeviceCommand command){
   vehicles.put(wired.vehicleId(),wired);
   var hand=mapping.handshake();
   var buffer=new byte[words*2];
   try{
    frames.write(mapping,out.slots(),view(command,wired),buffer,little);
   }catch(SlotCodec.SlotRejected e){
    // Nothing has been written to the controller: the command is wrong, not the link.
    log.warn("command {} does not fit the layout: {}",command.commandId(),e.getMessage());
    return Verdict.notDelivered("COMMAND_DOES_NOT_FIT");
   }
   var sequence=expectedEcho(mapping,out,command,wired);
   try{
    modbus.open();
   }catch(IOException e){ return Verdict.notDelivered("PLC_NOT_CONNECTED"); }
   try{
    // The values first, then the request bit: a controller that reads between the two sees a
    // command it has not been asked to take, which is the one order that is always safe.
    modbus.write(writeAt,words(buffer,hand,false));
    modbus.write(writeAt,words(buffer,hand,true));
   }catch(ModbusTcp.ModbusRefused e){
    return Verdict.notDelivered("PLC_REFUSED_"+e.code);
   }catch(IOException e){
    // Some of it may be in the registers, where it will stay until somebody looks.
    modbus.close();
    return Verdict.inDoubt("PLC_WRITE_FAILED");
   }
   var taken=awaitAcceptance(hand,sequence);
   try{ if(hand.clear()!=null) modbus.write(writeAt,words(buffer,hand,false)); }
   catch(IOException e){ log.warn("request bit on {}:{} could not be cleared: {}",host,port,e.getMessage()); }
   return taken?Verdict.delivered()
    :Verdict.inDoubt("PLC_NOT_ACKNOWLEDGED");   // the values are in the registers; nobody may say they were not taken
  }

  /** The buffer as registers, with the request bit set or clear. */
  private int[] words(byte[] buffer,VehicleMessageMapping.Handshake hand,boolean request){
   var copy=buffer.clone();
   var bit=mapping.layout().get(hand.thenSet());
   if(bit!=null) codec.write(copy,bit,json.getNodeFactory().booleanNode(request),little);
   var out=new int[words];
   for(int i=0;i<words;i++) out[i]=(copy[i*2]&0xFF)<<8|(copy[i*2+1]&0xFF);
   return out;
  }

  /** Waits for the controller's own bit, with the sequence it echoes saying which command it took. */
  private boolean awaitAcceptance(VehicleMessageMapping.Handshake hand,JsonNode sequence){
   long until=System.currentTimeMillis()+hand.timeoutMs();
   while(System.currentTimeMillis()<until){
    var slots=readBlock();
    if(slots!=null){
     var accepted=slots.get(hand.acceptedBit());
     var echo=slots.get(hand.echoSlot());
     if(accepted!=null&&accepted.asBoolean()
      &&(sequence==null||echo==null||echo.asLong()==sequence.asLong())) return true;
    }
    try{ Thread.sleep(50); }catch(InterruptedException e){ Thread.currentThread().interrupt(); return false; }
   }
   return false;
  }

  /** The read block as named values, or null when it could not be read. */
  private Map<String,JsonNode> readBlock(){
   try{
    var registers=modbus.read(readAt,words);
    var buffer=new byte[words*2];
    for(int i=0;i<words;i++){ buffer[i*2]=(byte)(registers[i]>>8); buffer[i*2+1]=(byte)registers[i]; }
    return frames.decode(mapping,buffer,little,"read");
   }catch(IOException e){
    modbus.close();
    return null;
   }
  }

  /** One pass of the reading, if this controller is due. */
  void pollOnce(){
   if(vehicles.isEmpty()||System.currentTimeMillis()<nextPollAt) return;
   nextPollAt=System.currentTimeMillis()+Math.max(50,mapping.area().pollMs());
   Map<String,JsonNode> slots;
   synchronized(this){ slots=readBlock(); }
   if(slots==null) return;
   frames.read(mapping,slots).ifPresent(decoded->vehicles.values().forEach(wired->write(wired,decoded.report())));
  }

  /**
   * What was read, written to the vehicle's row.
   *
   * <p>One block speaks for the vehicles on this controller. Where a block holds more than one,
   * the mapping names the vehicle in a slot and only that one is written; where it names none,
   * the controller front one line and the report is theirs.
   */
  private void write(Wired wired,MessageMapper.Report report){
   if(!report.saysAnything()) return;
   if(report.externalVehicleId()!=null&&!report.externalVehicleId().equals(wired.externalId())) return;
   Long revisionId=activeRevision(wired.siteId());
   if(revisionId==null) return;
   try{
    if(report.mode()!=null||report.status()!=null||report.nodeId()!=null)
     fleet.report(wired.siteId(),revisionId,wired.vehicleId(),new VehicleReport(report.nodeId(),
      report.online()==null||report.online(),"REMOTE".equals(report.mode()),"IDLE".equals(report.status()),
      Boolean.TRUE.equals(report.carrier()),"DEVICE",report.destinationChangeEnabled(),
      report.mode(),report.status()));
    if(report.batteryPct()!=null) fleet.reportBattery(wired.siteId(),revisionId,wired.vehicleId(),report.batteryPct());
   }catch(IllegalArgumentException e){
    log.warn("report for vehicle {} refused: {}",wired.vehicleId(),e.getMessage());
   }
  }
  void close(){ modbus.close(); }
 }

 /** What the controller will echo back to say which command it took. */
 private JsonNode expectedEcho(VehicleMessageMapping mapping,VehicleMessageMapping.Outbound out,
                               DeviceCommand command,Wired wired){
  var hand=mapping.handshake();
  if(hand==null||hand.echoSlot()==null) return null;
  // Whatever the document puts in the sequence slot is what it will compare against.
  for(var slot:out.slots().entrySet()){
   var described=mapping.layout().get(slot.getKey());
   if(described==null) continue;
   if(hand.write().contains(slot.getKey())&&slot.getKey().toLowerCase().contains("seq"))
    return rules.value(slot.getValue(),view(command,wired),null,null,null);
  }
  return null;
 }

 private MessageMapper.Command view(DeviceCommand command,Wired wired){
  JsonNode payload;
  try{ payload=json.readTree(command.payload()==null||command.payload().isBlank()?"{}":command.payload()); }
  catch(Exception e){ payload=json.createObjectNode(); }
  return new MessageMapper.Command(command.commandId(),command.commandType(),command.orderId(),wired.vehicleId(),
   wired.externalId(),command.attempt(),OffsetDateTime.now().toString(),payload,Map.of());
 }

 private Wired wiring(long siteId,long vehicleId){
  return jdbc.query("SELECT c.id,c.host,c.port,p.adapter_key,v.external_vehicle_id"
   +" FROM integration.vehicle_communication v"
   +" JOIN integration.connection_profile c ON c.site_id=v.site_id AND c.id=v.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
   +" WHERE v.site_id=? AND v.vehicle_id=? AND v.enabled AND c.enabled AND c.comm_type='TCP' AND c.role='CLIENT'",
   (r,n)->new Wired(siteId,r.getLong(1),r.getString(2),r.getInt(3),vehicleId,r.getString(4),r.getString(5)),
   siteId,vehicleId).stream().findFirst().orElse(null);
 }
 private Long activeRevision(long siteId){
  return jdbc.query("SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'",(r,n)->r.getLong(1),siteId)
   .stream().findFirst().orElse(null);
 }

 @PreDestroy void closeAll(){ controllers.values().forEach(Controller::close); controllers.clear(); }
}
