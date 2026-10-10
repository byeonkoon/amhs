package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/**
 * How one family of vehicles says what this controller means, as a document rather than as code.
 *
 * <p>Every site meets vehicles that speak differently: the same "go to these nodes" is one JSON
 * shape for one maker and another for the next, and the field that says a vehicle is under remote
 * control is called something different again. Writing an adapter per maker puts a site's wiring
 * into this repository and makes every new vehicle a release; writing the mapping down as data
 * keeps the decisions here and the dialect at the site, where it belongs and where it changes.
 *
 * <p>The document is deliberately not a language. There is no expression to evaluate, no script to
 * run: a field is taken from one of a fixed set of places, optionally translated through a table,
 * and written to a name this controller already knows. Anything a mapping cannot say is something
 * an adapter has to grow, in the open, with a test -- which is the right place for it.
 *
 * <p>The shape is documented in {@code docs/mapping-file.md}; a worked example is in
 * {@code docs/sample-mappings/}.
 */
public record VehicleMessageMapping(String code,String adapter,int version,String notes,
                                    Identity identity,Map<String,Outbound> outbound,List<Inbound> inbound){

 /** How a message says which vehicle it is about. */
 public record Identity(String fromTopic,String fromField){}
 /** One command, as the far side expects it: where it goes and what it carries. */
 public record Outbound(String topic,Map<String,Rule> body){}
 /** One kind of message coming back: how to recognise it and what it says. */
 public record Inbound(String whenTopic,Map<String,Rule> report){}

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
 public static final Set<String> SOURCES=Set.of("command","payload","body","topic","item","index");
 /** The only names a mapping may produce on a report: this controller's own vocabulary. */
 public static final Set<String> REPORT_FIELDS=
  Set.of("nodeId","nodeCode","mode","status","carrier","batteryPct","destinationChangeEnabled","online");

 public static VehicleMessageMapping read(ObjectMapper json,String document) throws IOException {
  var root=json.readTree(document);
  var mapping=new VehicleMessageMapping(
   text(root,"mapping"),text(root,"adapter"),root.path("version").asInt(1),text(root,"notes"),
   identity(root.path("identity")),outbound(root.path("outbound")),inbound(root.path("inbound")));
  mapping.validate();
  return mapping;
 }

 /** Everything wrong with the document, at once: a mapping is edited by hand and fixed by hand. */
 public void validate(){
  var wrong=new ArrayList<String>();
  if(code==null||code.isBlank()) wrong.add("mapping: 이름이 없습니다");
  if(adapter==null||adapter.isBlank()) wrong.add("adapter: 어떤 어댑터가 읽는 문서인지 없습니다");
  if(identity==null||(identity.fromTopic()==null&&identity.fromField()==null))
   wrong.add("identity: 메시지가 어느 차량의 것인지 알 길이 없습니다");
  outbound.forEach((command,out)->{
   if(out.topic()==null||out.topic().isBlank()) wrong.add("outbound."+command+": topic이 없습니다");
   out.body().forEach((field,rule)->check(wrong,"outbound."+command+"."+field,rule));
  });
  for(int i=0;i<inbound.size();i++){
   var in=inbound.get(i);
   if(in.whenTopic()==null||in.whenTopic().isBlank()) wrong.add("inbound["+i+"]: when.topic이 없습니다");
   int at=i;
   in.report().forEach((field,rule)->{
    if(!REPORT_FIELDS.contains(field)) wrong.add("inbound["+at+"]."+field+": 이 관제가 아는 보고 항목이 아닙니다 "+REPORT_FIELDS);
    check(wrong,"inbound["+at+"]."+field,rule);
   });
  }
  if(!wrong.isEmpty()) throw new MappingRejected(wrong);
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
  return new Identity(text(node,"fromTopic"),text(node,"fromField"));
 }
 private static Map<String,Outbound> outbound(JsonNode node){
  var out=new LinkedHashMap<String,Outbound>();
  node.fields().forEachRemaining(e->out.put(e.getKey(),new Outbound(text(e.getValue(),"topic"),rules(e.getValue().path("body")))));
  return Collections.unmodifiableMap(out);
 }
 private static List<Inbound> inbound(JsonNode node){
  var out=new ArrayList<Inbound>();
  node.forEach(rule->out.add(new Inbound(text(rule.path("when"),"topic"),rules(rule.path("report")))));
  return Collections.unmodifiableList(out);
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
