package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/**
 * How one family of machines says what this controller means, as a document rather than as code.
 *
 * <p>Every site meets equipment that speaks differently: the same "go to these nodes" is one JSON
 * shape for one maker and another for the next, and the field that says a vehicle is under remote
 * control is called something different again. Writing an adapter per maker puts a site's wiring
 * into this repository and makes every new machine a release; writing the mapping down as data
 * keeps the decisions here and the dialect at the site, where it belongs and where it changes.
 *
 * <p>A family is a family of vehicles or a family of equipment -- a stocker behind a PLC is the
 * same arrangement as an AGV behind one, described in the same words. What differs between the two
 * is only what a document of that adapter may report, because what a vehicle has to say about
 * itself is not what a stocker has.
 *
 * <p>The document is deliberately not a language. There is no expression to evaluate, no script to
 * run: a field is taken from one of a fixed set of places, optionally translated through a table,
 * and written to a name this controller already knows. Anything a mapping cannot say is something
 * an adapter has to grow, in the open, with a test -- which is the right place for it.
 *
 * <p>The shape is documented in {@code docs/mapping-file.md}; a worked example is in
 * {@code docs/sample-mappings/}.
 */
public record DeviceMessageMapping(String code,String adapter,int version,String notes,
                                    Identity identity,Map<String,Outbound> outbound,List<Inbound> inbound,
                                    Frame frame,Area area,Map<String,Slot> layout,Handshake handshake){

 /** How a message says which machine it is about -- a vehicle on one link, a stocker on another. */
 public record Identity(String fromTopic,String fromField,String fromSlot){}
 /** One command, as the far side expects it: where it goes and what it carries. */
 public record Outbound(String topic,Map<String,Rule> body,Map<String,Rule> slots){}
 /**
  * One kind of message coming back: how to recognise it and what it says.
  *
  * <p>Recognised by its topic where messages have topics, and by the value in a slot where they do
  * not. {@code always} is for a link that has no kinds at all -- a block of registers read round
  * and round, where every read is the same message and the only question is what is in it now.
  */
 public record Inbound(String whenTopic,Map<String,JsonNode> whenSlots,boolean always,
                       Map<String,Rule> report,Map<String,Rule> ack){}

 /**
  * A frame of bytes: how long it is, how it begins and ends, and how it is checked.
  *
  * <p>A byte protocol has no names in it. Everything is a position, which is why the positions are
  * written here once and referred to by name everywhere else: the site that changes a vendor's
  * offset changes one block, not every command.
  */
 public record Frame(Integer size,Integer start,Integer end,String endian,Checksum checksum){}
 public record Checksum(String type,int at,int from,int to){}
 /**
  * A block of PLC registers: the words written to, the words read back, and how often they are read.
  *
  * <p>A PLC is not a correspondent. Nothing arrives; a block of words is read round and round and
  * the difference is the news. The poll interval is therefore how fresh any position can be, and
  * a site whose stale-position limit is shorter than its poll has set itself an impossible rule.
  *
  * <p>{@code driver} is which wire carries the block, and it belongs here rather than in a second
  * adapter because everything else about the document is the same either way: the same layout, the
  * same handshake, the same rules. Only how a word is asked for differs, and how an address is
  * written -- {@code 40100} on Modbus, {@code D100} on MELSEC -- which is the driver's to read.
  */
 public record Area(String write,String read,int words,String endian,int pollMs,String driver){}
 /**
  * How a PLC says it has taken a command.
  *
  * <p>Writing registers is not telling anybody anything: the value simply sits there. So a command
  * is the values, then a request bit, and it counts as taken only when the controller raises its
  * own bit and echoes back the sequence it took. Without the echo, an acknowledgement cannot be
  * told from the one before it -- and a register, unlike a message, is still there to be acted on,
  * which is why a repeat that nobody can distinguish is the dangerous case here.
  */
 public record Handshake(List<String> write,String thenSet,String acceptedBit,String echoSlot,
                         String clear,int timeoutMs){}
 /**
  * Where one named value sits and what it is.
  *
  * <p>{@code at} is a byte offset and {@code word} a 16-bit word (word n is byte 2n), so a frame
  * protocol and a register block are described the same way. {@code repeat} is a run of the same
  * type; {@code scale} turns the integer on the wire into the number it stands for.
  */
 public record Slot(String name,int at,String type,Integer bit,int length,int repeat,double scale,String area,
                    boolean inWord){}

 /**
  * One value.
  *
  * <p>{@code from} names where it comes from, in a closed vocabulary -- {@code command.*} and
  * {@code payload.*} going out, {@code body.*} and {@code topic.*} coming back. {@code constant} is
  * a literal. {@code values} translates what was found through a table, which is how a maker's
  * word for "under remote control" becomes this controller's. {@code op} is one of a short list of
  * conversions. {@code each} maps a list element by element.
  */
 public record Rule(String from,JsonNode constant,Map<String,JsonNode> values,String op,JsonNode fallback,
                    Map<String,Rule> each){
  public boolean maps(){ return from!=null||constant!=null||each!=null; }
 }

 /** What a document may ask for, so a typo is refused when it is read rather than ignored when it runs. */
 public static final Set<String> OPS=Set.of("int","number","text","bool","negate","count","notEmpty","first","last");
 public static final Set<String> SOURCES=Set.of("command","payload","body","topic","slot","item","index");
 /** What a value on a wire may be. Nothing here is guessed from the data: a slot says what it is. */
 public static final Set<String> TYPES=Set.of("u8","u16","u32","i8","i16","i32","bcd","ascii","bit");
 /** Modbus TCP holding registers, written {@code 40100} or as the wire address itself. */
 public static final String MODBUS="modbus";
 /** MELSEC communication protocol, 3E binary. Addresses are written {@code D100}, {@code W1A0}. */
 public static final String MC="mc";
 /** The wires a block of words can be carried on. Which one is the block's own property. */
 public static final Set<String> DRIVERS=Set.of(MODBUS,MC);
 /** The only names a mapping may produce on a vehicle's report: this controller's own vocabulary. */
 public static final Set<String> VEHICLE_FIELDS=
  Set.of("nodeId","nodeCode","mode","status","carrier","batteryPct","destinationChangeEnabled","online");
 /**
  * The same, for a piece of equipment, which is a different vocabulary because it is a different
  * thing: a stocker has no node and no battery. It has whether it is able at all, whether it will
  * take a call now, what it is doing, and whether anybody has heard from it.
  */
 public static final Set<String> EQUIPMENT_FIELDS=Set.of("status","available","callEnabled","online");
 /**
  * The only thing this controller says to a piece of equipment.
  *
  * <p>A closed list of one, because there is one decision: a vehicle has been sent to that port.
  * A document naming anything else has described a command nothing will ever send, and a dead
  * command in a document is worse than none -- somebody wrote it believing it would go.
  */
 public static final Set<String> EQUIPMENT_COMMANDS=Set.of("PORT_INTENT");

 /** Adapters that address values by name in a structure. */
 public static final Set<String> NAMED=Set.of("JSON_LINE_V1","MQTT_JSON_V1");
 /** Adapters that address values by position in bytes. */
 public static final Set<String> POSITIONAL=Set.of("PC_FRAME_V1","PLC_WORD_V1","EQUIPMENT_WORD_V1");
 /** Adapters that work a block of words: the same arrangement, a vehicle behind it or a machine. */
 public static final Set<String> WORD_BLOCK=Set.of("PLC_WORD_V1","EQUIPMENT_WORD_V1");

 public static DeviceMessageMapping read(ObjectMapper json,String document) throws IOException {
  var root=json.readTree(document);
  var mapping=new DeviceMessageMapping(
   text(root,"mapping"),text(root,"adapter"),root.path("version").asInt(1),text(root,"notes"),
   identity(root.path("identity")),outbound(root.path("outbound")),inbound(root.path("inbound")),
   frame(root.path("frame")),area(root.path("area")),layout(root.path("layout")),
   handshake(root.path("handshake")));
  mapping.validate();
  return mapping;
 }

 /**
  * Everything wrong with the document, at once: a mapping is edited by hand and fixed by hand.
  *
  * <p>What a document must have, and must not have, follows from its adapter. A block that adapter
  * has no use for is refused rather than ignored -- a topic in a register mapping is somebody
  * believing their vehicle is addressed by one, and finding out later costs a day on a floor.
  */
 public void validate(){
  var wrong=new ArrayList<String>();
  if(code==null||code.isBlank()) wrong.add("mapping: 이름이 없습니다");
  if(adapter==null||adapter.isBlank()){
   wrong.add("adapter: 어떤 어댑터가 읽는 문서인지 없습니다");
   throw new MappingRejected(wrong);     // without it, nothing else can be judged
  }
  boolean named=NAMED.contains(adapter), positional=POSITIONAL.contains(adapter);
  boolean words=WORD_BLOCK.contains(adapter), equipment="EQUIPMENT_WORD_V1".equals(adapter);
  if(!named&&!positional) wrong.add("adapter: "+adapter+"를 읽는 어댑터가 없습니다 "+union(NAMED,POSITIONAL));

  if(identity==null||(identity.fromTopic()==null&&identity.fromField()==null&&identity.fromSlot()==null))
   wrong.add("identity: 메시지가 어느 "+(equipment?"설비":"차량")+"의 것인지 알 길이 없습니다");
  if(positional&&identity!=null&&identity.fromTopic()!=null)
   wrong.add("identity.fromTopic: "+adapter+"에는 토픽이 없습니다 (fromSlot을 씁니다)");

  if(positional&&layout.isEmpty()) wrong.add("layout: "+adapter+"는 값이 어디에 있는지 적어야 합니다");
  if(named&&!layout.isEmpty()) wrong.add("layout: "+adapter+"는 이름으로 찾으므로 자리를 적지 않습니다");
  if("PC_FRAME_V1".equals(adapter)&&frame==null) wrong.add("frame: PC_FRAME_V1은 프레임 길이·검사를 적어야 합니다");
  if(words&&area==null) wrong.add("area: "+adapter+"는 읽고 쓸 레지스터 블록을 적어야 합니다");
  // A register keeps the value that was written to it. Without a handshake nobody can say whether
  // the controller has taken a command or is about to take it again, and a repeat is a second move.
  // Equipment that is only read has nothing to acknowledge, so it needs one only if it is written.
  if(words&&handshake==null&&(!equipment||!outbound.isEmpty()))
   wrong.add("handshake: "+adapter+"는 "+(equipment?"명령을 적었으면 ":"")+"수락 확인이 필수입니다 (레지스터는 값이 남습니다)");
  if(!words&&handshake!=null) wrong.add("handshake: "+adapter+"는 핸드셰이크를 쓰지 않습니다");
  if(words&&area!=null&&area.pollMs()<=0) wrong.add("area.poll-ms: 읽는 주기가 없으면 보고가 오지 않습니다");
  if(words&&area!=null&&!DRIVERS.contains(area.driver()))
   wrong.add("area.driver: "+area.driver()+"로 말하는 선은 없습니다 "+DRIVERS);
  // Equipment is read. Where a document says nothing is read, there is nothing it can ever say.
  if(equipment&&inbound.isEmpty()) wrong.add("inbound: EQUIPMENT_WORD_V1은 설비가 무엇을 말하는지 적어야 합니다");
  if(equipment&&area!=null&&(area.read()==null||area.read().isBlank()))
   wrong.add("area.read: 설비가 무엇을 말하는지는 읽어서만 알 수 있으므로 읽을 블록이 있어야 합니다");
  // Writing is the one statement, and only that one. A block to write it into is part of saying it.
  if(equipment&&!outbound.isEmpty()){
   if(area!=null&&(area.write()==null||area.write().isBlank()))
    wrong.add("area.write: 설비에 알리려면 쓸 블록이 있어야 합니다");
   outbound.keySet().forEach(command->{
    if(!EQUIPMENT_COMMANDS.contains(command))
     wrong.add("outbound."+command+": 이 관제가 설비에 말하는 것은 "+EQUIPMENT_COMMANDS+"뿐입니다");
   });
  }

  layout.forEach((name,slot)->{
   if(!TYPES.contains(slot.type())) wrong.add("layout."+name+": type "+slot.type()+"는 없습니다 "+TYPES);
   if("bit".equals(slot.type())&&(slot.bit()==null||slot.bit()<0||slot.bit()>15))
    wrong.add("layout."+name+": bit 슬롯은 0~15의 bit를 적어야 합니다");
   if(("ascii".equals(slot.type())||"bcd".equals(slot.type()))&&slot.length()<=0)
    wrong.add("layout."+name+": "+slot.type()+"는 length가 있어야 합니다");
   if(slot.at()<0) wrong.add("layout."+name+": at·word는 0 이상이어야 합니다");
  });

  outbound.forEach((command,out)->{
   if(named){
    if(out.topic()==null||out.topic().isBlank()) wrong.add("outbound."+command+": topic이 없습니다");
    if(!out.slots().isEmpty()) wrong.add("outbound."+command+".slots: "+adapter+"는 body를 씁니다");
    out.body().forEach((field,rule)->check(wrong,"outbound."+command+"."+field,rule));
   }else{
    if(out.topic()!=null) wrong.add("outbound."+command+".topic: "+adapter+"에는 토픽이 없습니다");
    if(out.slots().isEmpty()) wrong.add("outbound."+command+": slots가 없습니다");
    out.slots().forEach((name,rule)->{
     if(!layout.containsKey(name)) wrong.add("outbound."+command+"."+name+": layout에 없는 슬롯입니다");
     check(wrong,"outbound."+command+"."+name,rule);
    });
   }
  });

  for(int i=0;i<inbound.size();i++){
   var in=inbound.get(i);
   int at=i;
   if(named&&(in.whenTopic()==null||in.whenTopic().isBlank())) wrong.add("inbound["+i+"]: when.topic이 없습니다");
   if(positional){
    if(in.whenTopic()!=null) wrong.add("inbound["+i+"].when.topic: "+adapter+"에는 토픽이 없습니다");
    if(in.whenSlots().isEmpty()&&!in.always())
     wrong.add("inbound["+i+"]: when에 슬롯 값이나 always가 있어야 합니다");
    in.whenSlots().keySet().forEach(name->{
     if(!layout.containsKey(name)) wrong.add("inbound["+at+"].when."+name+": layout에 없는 슬롯입니다");
    });
   }
   var allowed=equipment?EQUIPMENT_FIELDS:VEHICLE_FIELDS;
   in.report().forEach((field,rule)->{
    if(!allowed.contains(field)) wrong.add("inbound["+at+"]."+field+": 이 관제가 아는 보고 항목이 아닙니다 "+allowed);
    check(wrong,"inbound["+at+"]."+field,rule);
   });
   in.ack().forEach((field,rule)->check(wrong,"inbound["+at+"].ack."+field,rule));
  }

  if(handshake!=null){
   handshake.write().forEach(name->{
    if(!layout.containsKey(name)) wrong.add("handshake.write: layout에 없는 슬롯입니다 ("+name+")");
   });
   // Arrays.asList rather than List.of: every one of these may be absent, and a document that
   // left one out has to be told so rather than throw on the way to being told.
   for(var named_:java.util.Arrays.asList(handshake.thenSet(),handshake.acceptedBit(),handshake.clear()))
    if(named_!=null&&!layout.containsKey(named_)) wrong.add("handshake: layout에 없는 슬롯입니다 ("+named_+")");
   if(handshake.thenSet()==null)
    wrong.add("handshake.then-set: 무엇을 세워 요청하는지 적어야 합니다 (값만 써 두면 아무도 집어가지 않습니다)");
   if(handshake.acceptedBit()==null) wrong.add("handshake.accepted-bit: 무엇이 수락인지 적어야 합니다");
   if(handshake.echoSlot()==null)
    wrong.add("handshake.echo-slot: 수락이 어느 명령에 대한 것인지 가릴 수 없습니다 (시퀀스 에코가 필요합니다)");
  }
  if(!wrong.isEmpty()) throw new MappingRejected(wrong);
 }
 private static Set<String> union(Set<String> a,Set<String> b){
  var all=new java.util.TreeSet<>(a); all.addAll(b); return all;
 }

 private static void check(List<String> wrong,String where,Rule rule){
  if(!rule.maps()) wrong.add(where+": from·constant·each 가운데 하나는 있어야 합니다");
  if(rule.from()!=null&&!SOURCES.contains(rule.from().split("\\.")[0]))
   wrong.add(where+": from은 "+SOURCES+" 가운데 하나로 시작해야 합니다 ("+rule.from()+")");
  if(rule.op()!=null&&!OPS.contains(rule.op())) wrong.add(where+": op "+rule.op()+"는 없습니다 "+OPS);
  if(rule.each()!=null&&rule.from()==null) wrong.add(where+": each는 from이 가리키는 목록이 있어야 합니다");
  if(rule.each()!=null) rule.each().forEach((field,inner)->check(wrong,where+".each."+field,inner));
 }

 private static Identity identity(JsonNode node){
  if(node.isMissingNode()) return null;
  return new Identity(text(node,"fromTopic"),text(node,"fromField"),text(node,"fromSlot"));
 }
 private static Map<String,Outbound> outbound(JsonNode node){
  var out=new LinkedHashMap<String,Outbound>();
  node.fields().forEachRemaining(e->out.put(e.getKey(),
   new Outbound(text(e.getValue(),"topic"),rules(e.getValue().path("body")),rules(e.getValue().path("slots")))));
  return Collections.unmodifiableMap(out);
 }
 private static List<Inbound> inbound(JsonNode node){
  var out=new ArrayList<Inbound>();
  node.forEach(rule->{
   var when=rule.path("when");
   var slots=new LinkedHashMap<String,JsonNode>();
   when.fields().forEachRemaining(e->{ if(!e.getKey().equals("topic")&&!e.getKey().equals("always")) slots.put(e.getKey(),e.getValue()); });
   out.add(new Inbound(text(when,"topic"),Collections.unmodifiableMap(slots),when.path("always").asBoolean(false),
    rules(rule.path("report")),rules(rule.path("ack"))));
  });
  return Collections.unmodifiableList(out);
 }
 private static Frame frame(JsonNode node){
  if(node.isMissingNode()) return null;
  var check=node.path("checksum");
  return new Frame(node.has("size")?node.get("size").asInt():null,number(node,"start"),number(node,"end"),
   text(node,"endian"),check.isMissingNode()?null
    :new Checksum(text(check,"type"),check.path("at").asInt(),check.path("over").path(0).asInt(),
      check.path("over").path(1).asInt()));
 }
 private static Area area(JsonNode node){
  if(node.isMissingNode()) return null;
  // Modbus when the document does not say, because that is what every document written before
  // there was a choice meant.
  var driver=text(node,"driver");
  return new Area(text(node,"write"),text(node,"read"),node.path("words").asInt(0),text(node,"endian"),
   node.path("poll-ms").asInt(0),driver==null?MODBUS:driver.toLowerCase(java.util.Locale.ROOT));
 }
 private static Handshake handshake(JsonNode node){
  if(node.isMissingNode()) return null;
  var write=new ArrayList<String>();
  node.path("write").forEach(name->write.add(name.asText()));
  return new Handshake(List.copyOf(write),text(node,"then-set"),text(node,"accepted-bit"),text(node,"echo-slot"),
   text(node,"clear"),node.path("timeout-ms").asInt(2000));
 }
 /**
  * The layout, by name. {@code word} is the same thing as {@code at} two bytes at a time, which is
  * how a register block and a byte frame come to be described in one vocabulary.
  */
 private static Map<String,Slot> layout(JsonNode node){
  var out=new LinkedHashMap<String,Slot>();
  node.fields().forEachRemaining(e->{
   var slot=e.getValue();
   int at=slot.has("word")?slot.get("word").asInt()*2:slot.path("at").asInt(-1);
   out.put(e.getKey(),new Slot(e.getKey(),at,slot.path("type").asText(slot.has("bit")?"bit":"u16"),
    slot.has("bit")?slot.get("bit").asInt():null,slot.path("length").asInt(0),
    Math.max(1,slot.path("repeat").asInt(1)),slot.path("scale").asDouble(1.0),
    slot.path("area").asText("write"),slot.has("word")));
  });
  return Collections.unmodifiableMap(out);
 }
 /** A number written as a number or as "0x1F", because a byte protocol is read in hexadecimal. */
 private static Integer number(JsonNode node,String field){
  var value=node.path(field);
  if(value.isMissingNode()||value.isNull()) return null;
  if(value.isNumber()) return value.asInt();
  var text=value.asText().trim();
  return text.startsWith("0x")||text.startsWith("0X")?Integer.parseInt(text.substring(2),16):Integer.parseInt(text);
 }
 private static Map<String,Rule> rules(JsonNode node){
  var out=new LinkedHashMap<String,Rule>();
  node.fields().forEachRemaining(e->out.put(e.getKey(),rule(e.getValue())));
  return Collections.unmodifiableMap(out);
 }
 private static Rule rule(JsonNode node){
  // A bare value is the short way of writing a constant, which is most of what a mapping says.
  if(!node.isObject()) return new Rule(null,node,null,null,null,null);
  Map<String,JsonNode> values=null;
  if(node.has("values")){
   values=new LinkedHashMap<>();
   var table=values;
   node.get("values").fields().forEachRemaining(e->table.put(e.getKey(),e.getValue()));
  }
  return new Rule(text(node,"from"),node.has("constant")?node.get("constant"):null,
   values==null?null:Collections.unmodifiableMap(values),text(node,"op"),
   node.has("default")?node.get("default"):null,node.has("each")?rules(node.get("each")):null);
 }
 private static String text(JsonNode node,String field){
  var value=node.path(field);
  return value.isMissingNode()||value.isNull()?null:value.asText();
 }

 /** What is wrong with a mapping document, all of it, in the words of the person who wrote it. */
 public static final class MappingRejected extends RuntimeException {
  private final List<String> problems;
  MappingRejected(List<String> problems){ super(String.join("; ",problems)); this.problems=List.copyOf(problems); }
  public List<String> problems(){ return problems; }
 }
}
