package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Commands into bytes and bytes into reports, for the protocols that address by position.
 *
 * <p>The same mapping document, the same value rules; only the place a value goes is a slot rather
 * than a field. That is deliberate: a site that has learnt how one of its vehicles is described has
 * learnt how all of them are, whether they speak JSON over a broker or sixteen registers on a PLC.
 *
 * <p>A frame is built whole and checked whole. There is no partial frame: a value that does not fit
 * stops the command before anything is written, because half a command on a wire is worse than
 * none, and a vehicle acting on a truncated node number is the accident this exists to prevent.
 */
public final class FrameMapper {
 private final ObjectMapper json; private final MessageMapper rules; private final SlotCodec codec;

 public FrameMapper(ObjectMapper json,MessageMapper rules,SlotCodec codec){
  this.json=json; this.rules=rules; this.codec=codec;
 }

 /** What a received buffer said: a report, an acknowledgement, or neither. */
 public record Decoded(String externalVehicleId,MessageMapper.Report report,String ackOf,Map<String,JsonNode> slots){}

 /** The frame for this command, or empty when the document does not describe it. */
 public Optional<byte[]> toFrame(VehicleMessageMapping mapping,MessageMapper.Command command){
  var out=mapping.outbound().get(command.commandType());
  if(out==null||out.slots().isEmpty()) return Optional.empty();
  var frame=mapping.frame();
  int size=frame!=null&&frame.size()!=null?frame.size():0;
  if(size<=0) throw new SlotCodec.SlotRejected("frame.size: 프레임 길이가 없습니다");
  var buffer=new byte[size];
  boolean little=little(frame==null?null:frame.endian());
  if(frame!=null&&frame.start()!=null) buffer[0]=(byte)(int)frame.start();
  if(frame!=null&&frame.end()!=null) buffer[size-1]=(byte)(int)frame.end();
  write(mapping,out.slots(),command,buffer,little);
  if(frame!=null&&frame.checksum()!=null)
   buffer[frame.checksum().at()]=(byte)codec.checksum(buffer,frame.checksum());
  return Optional.of(buffer);
 }

 /** Fills the named slots of a buffer from the rules that describe them. Used for frames and registers alike. */
 public void write(VehicleMessageMapping mapping,Map<String,VehicleMessageMapping.Rule> slots,
                   MessageMapper.Command command,byte[] buffer,boolean little){
  slots.forEach((name,rule)->{
   var slot=mapping.layout().get(name);
   if(slot==null) throw new SlotCodec.SlotRejected("slot "+name+": layout에 없습니다");
   // A document writes a byte protocol's constants the way its documentation does, in hexadecimal.
   codec.write(buffer,slot,numeric(json,rules.value(rule,command,null,null,null)),little);
  });
 }

 /** Every slot of the layout as it reads now, by name. */
 public Map<String,JsonNode> decode(VehicleMessageMapping mapping,byte[] buffer,boolean little,String area){
  var out=new LinkedHashMap<String,JsonNode>();
  mapping.layout().forEach((name,slot)->{
   if(area!=null&&!area.equals(slot.area())) return;
   var value=codec.read(buffer,slot,little);
   if(value!=null) out.put(name,value);
  });
  return out;
 }

 /**
  * What this buffer says, by the first inbound rule that recognises it.
  *
  * <p>A frame whose checksum does not add up is not read at all. There is no "probably fine": the
  * check is the only thing standing between a corrupted node number and a vehicle driving to it.
  */
 public Optional<Decoded> fromFrame(VehicleMessageMapping mapping,byte[] buffer){
  var frame=mapping.frame();
  boolean little=little(frame==null?null:frame.endian());
  if(frame!=null&&frame.checksum()!=null){
   int at=frame.checksum().at();
   if(at<0||at>=buffer.length) return Optional.empty();
   if((buffer[at]&0xFF)!=codec.checksum(buffer,frame.checksum()))
    throw new SlotCodec.SlotRejected("프레임 검사 값이 맞지 않습니다");
  }
  var slots=decode(mapping,buffer,little,null);
  return read(mapping,slots);
 }

 /** The same, for a block of registers that has already been decoded. */
 public Optional<Decoded> read(VehicleMessageMapping mapping,Map<String,JsonNode> slots){
  for(var rule:mapping.inbound()){
   if(!matches(rule,slots)) continue;
   var values=new LinkedHashMap<String,JsonNode>();
   rule.report().forEach((field,field_)->{
    var v=rules.value(field_,null,null,null,slots);
    if(v!=null&&!v.isNull()) values.put(field,v);
   });
   String ackOf=null;
   var ackRule=rule.ack().get("sequence");
   if(ackRule!=null){
    var value=rules.value(ackRule,null,null,null,slots);
    ackOf=value==null||value.isNull()?null:value.asText();
   }
   var identity=mapping.identity();
   String vehicle=identity==null||identity.fromSlot()==null?null
    :slots.get(identity.fromSlot())==null?null:slots.get(identity.fromSlot()).asText();
   var report=new MessageMapper.Report(vehicle,
    asLong(values.get("nodeId")),asText(values.get("nodeCode")),asText(values.get("mode")),asText(values.get("status")),
    asBool(values.get("carrier")),asDouble(values.get("batteryPct")),
    asBool(values.get("destinationChangeEnabled")),asBool(values.get("online")));
   return Optional.of(new Decoded(vehicle,report,ackOf,slots));
  }
  return Optional.empty();
 }

 /** Whether this rule is the one: every slot it names holds the value it names. */
 private static boolean matches(VehicleMessageMapping.Inbound rule,Map<String,JsonNode> slots){
  if(rule.always()) return true;
  if(rule.whenSlots().isEmpty()) return false;
  for(var want:rule.whenSlots().entrySet()){
   var found=slots.get(want.getKey());
   if(found==null) return false;
   // Compared as numbers when both are, so 0x81 and 129 are the same answer written two ways.
   if(found.isNumber()&&want.getValue().isNumber()){ if(found.asLong()!=want.getValue().asLong()) return false; }
   else if(!text(want.getValue()).equals(found.asText())) return false;
  }
  return true;
 }
 /** A hexadecimal string in a document means the number it spells. */
 private static String text(JsonNode node){
  if(!node.isTextual()) return node.asText();
  var value=node.asText().trim();
  return value.startsWith("0x")||value.startsWith("0X")
   ?String.valueOf(Long.parseLong(value.substring(2),16)):value;
 }
 /** A when-value written in hex has to be compared as the number it is. */
 public static JsonNode numeric(ObjectMapper json,JsonNode node){
  if(node==null||!node.isTextual()) return node;
  var value=node.asText().trim();
  return value.startsWith("0x")||value.startsWith("0X")
   ?json.getNodeFactory().numberNode(Long.parseLong(value.substring(2),16)):node;
 }
 private static boolean little(String endian){ return "little".equalsIgnoreCase(endian); }
 private static Long asLong(JsonNode n){ return n==null||n.isNull()?null:n.asLong(); }
 private static Double asDouble(JsonNode n){ return n==null||n.isNull()?null:n.asDouble(); }
 private static Boolean asBool(JsonNode n){ return n==null||n.isNull()?null:n.asBoolean(); }
 private static String asText(JsonNode n){ return n==null||n.isNull()?null:n.asText(); }
}
