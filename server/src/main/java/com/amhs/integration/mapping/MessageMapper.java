package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a mapping document: a command becomes a message, and a message becomes a report.
 *
 * <p>Nothing here decides anything. It copies values from one shape to another by the rules the
 * document gives, and where the document says nothing it produces nothing -- a field left out is
 * left out, never guessed at and never defaulted to something that reads as healthy. What it
 * refuses to do is the point: there is no expression to evaluate, so a mapping cannot do anything
 * an operator reading it would not expect.
 */
public final class MessageMapper {
 private final ObjectMapper json;
 public MessageMapper(ObjectMapper json){ this.json=json; }

 /** What a command looks like to a mapping: the decision, and the names the far side knows. */
 public record Command(String commandId,String commandType,Long orderId,long vehicleId,String externalId,
                       int sequence,String sentAt,JsonNode payload,Map<String,String> identity){}
 /** Where a message goes and what it carries. */
 public record Message(String topic,ObjectNode body){}
 /**
  * What a message said, in this controller's own words. Every field is optional: a vehicle that
  * does not say where it is has not said it, and a report that carried nothing is not a report.
  */
 public record Report(String externalVehicleId,Long nodeId,String nodeCode,String mode,String status,
                      Boolean carrier,Double batteryPct,Boolean destinationChangeEnabled,Boolean online){
  public boolean saysAnything(){
   return nodeId!=null||nodeCode!=null||mode!=null||status!=null||carrier!=null||batteryPct!=null
    ||destinationChangeEnabled!=null||online!=null;
  }
 }

 /** The message for this command, or empty when the mapping does not describe that command. */
 public Optional<Message> toMessage(VehicleMessageMapping mapping,Command command){
  var out=mapping.outbound().get(command.commandType());
  if(out==null) return Optional.empty();
  var body=json.createObjectNode();
  out.body().forEach((field,rule)->set(body,field,value(rule,command,null,null,null)));
  return Optional.of(new Message(fill(out.topic(),command.identity()),body));
 }

 /** What this message says, or empty when no rule recognises its topic. */
 public Optional<Report> toReport(VehicleMessageMapping mapping,String topic,JsonNode body){
  for(var rule:mapping.inbound()){
   var captured=match(rule.whenTopic(),topic);
   if(captured==null) continue;
   var values=new LinkedHashMap<String,JsonNode>();
   rule.report().forEach((field,field_)->{ var v=value(field_,null,body,captured,null); if(v!=null&&!v.isNull()) values.put(field,v); });
   var vehicle=vehicleOf(mapping,captured,body);
   var report=new Report(vehicle,
    asLong(values.get("nodeId")),asText(values.get("nodeCode")),asText(values.get("mode")),asText(values.get("status")),
    asBool(values.get("carrier")),asDouble(values.get("batteryPct")),
    asBool(values.get("destinationChangeEnabled")),asBool(values.get("online")));
   return Optional.of(report);
  }
  return Optional.empty();
 }

 /** Which vehicle the far side means, by the topic it used or a field it carried. */
 private String vehicleOf(VehicleMessageMapping mapping,Map<String,String> captured,JsonNode body){
  var identity=mapping.identity();
  if(identity==null) return null;
  if(identity.fromTopic()!=null) return captured.get(identity.fromTopic());
  var found=path(body,identity.fromField().startsWith("body.")?identity.fromField().substring(5):identity.fromField());
  return found==null||found.isNull()?null:found.asText();
 }

 /**
  * One value, by the rule that describes it.
  *
  * <p>Order matters and is the document's contract: take it from where it comes from, translate it
  * through the table if there is one, convert it if asked, and fall back only when what came out
  * was nothing.
  */
 public JsonNode value(VehicleMessageMapping.Rule rule,Command command,JsonNode body,Map<String,String> captured,
                 Map<String,JsonNode> slots){
  JsonNode found=rule.constant()!=null?rule.constant():source(rule.from(),command,body,captured,slots,null,-1);
  if(rule.each()!=null&&found!=null&&found.isArray()){
   var list=json.createArrayNode();
   for(int i=0;i<found.size();i++){
    var element=json.createObjectNode();
    int index=i; var item=found.get(i);
    rule.each().forEach((field,inner)->set(element,field,each(inner,command,body,captured,slots,item,index)));
    list.add(element);
   }
   found=list;
  }
  if(rule.values()!=null&&found!=null&&!found.isNull()){
   var translated=rule.values().get(found.asText());
   // A value the table does not name is not passed through: a mapping that lists what it knows is
   // saying that anything else is something it does not, and guessing there is how a vehicle in an
   // unknown state reads as a vehicle in a known one.
   found=translated;
  }
  if(rule.op()!=null) found=apply(rule.op(),found);
  if((found==null||found.isNull())&&rule.fallback()!=null) found=rule.fallback();
  return found;
 }
 private JsonNode each(VehicleMessageMapping.Rule rule,Command command,JsonNode body,Map<String,String> captured,
                       Map<String,JsonNode> slots,JsonNode item,int index){
  JsonNode found=rule.constant()!=null?rule.constant():source(rule.from(),command,body,captured,slots,item,index);
  if(rule.values()!=null&&found!=null&&!found.isNull()) found=rule.values().get(found.asText());
  if(rule.op()!=null) found=apply(rule.op(),found);
  if((found==null||found.isNull())&&rule.fallback()!=null) found=rule.fallback();
  return found;
 }

 private JsonNode source(String from,Command command,JsonNode body,Map<String,String> captured,
                         Map<String,JsonNode> slots,JsonNode item,int index){
  if(from==null) return null;
  int dot=from.indexOf('.');
  String root=dot<0?from:from.substring(0,dot), rest=dot<0?"":from.substring(dot+1);
  return switch(root){
   case "item" -> rest.isEmpty()?item:path(item,rest);
   case "index" -> json.getNodeFactory().numberNode(index);
   case "topic" -> captured==null||captured.get(rest)==null?null:json.getNodeFactory().textNode(captured.get(rest));
   case "slot" -> slots==null?null:slots.get(rest);
   case "body" -> path(body,rest);
   case "payload" -> command==null?null:path(command.payload(),rest);
   case "command" -> command==null?null:switch(rest){
    case "commandId" -> json.getNodeFactory().textNode(command.commandId());
    case "commandType" -> json.getNodeFactory().textNode(command.commandType());
    case "orderId" -> command.orderId()==null?null:json.getNodeFactory().numberNode(command.orderId());
    case "vehicleId" -> json.getNodeFactory().numberNode(command.vehicleId());
    case "externalId" -> json.getNodeFactory().textNode(command.externalId());
    case "sequence" -> json.getNodeFactory().numberNode(command.sequence());
    case "sentAt" -> command.sentAt()==null?null:json.getNodeFactory().textNode(command.sentAt());
    default -> null;
   };
   default -> null;
  };
 }

 private JsonNode apply(String op,JsonNode found){
  var factory=json.getNodeFactory();
  if(found==null||found.isNull())
   // Only the two that are answers about absence may be computed from nothing.
   return switch(op){ case "count" -> factory.numberNode(0); case "notEmpty" -> factory.booleanNode(false); default -> null; };
  return switch(op){
   case "int" -> factory.numberNode(found.asLong());
   case "number" -> factory.numberNode(found.asDouble());
   case "text" -> factory.textNode(found.asText());
   case "bool" -> factory.booleanNode(found.asBoolean());
   case "negate" -> factory.booleanNode(!found.asBoolean());
   case "count" -> factory.numberNode(found.isArray()?found.size():1);
   case "notEmpty" -> factory.booleanNode(found.isArray()?found.size()>0:!found.asText().isEmpty());
   case "first" -> found.isArray()&&found.size()>0?found.get(0):found;
   case "last" -> found.isArray()&&found.size()>0?found.get(found.size()-1):found;
   default -> found;
  };
 }

 /** A dotted path, with {@code name[0]} for a list element. Missing is null, never an empty value. */
 private static JsonNode path(JsonNode from,String path){
  if(from==null||path==null||path.isEmpty()) return from;
  JsonNode at=from;
  for(String step:path.split("\\.")){
   if(at==null) return null;
   var index=step.indexOf('[');
   if(index>=0){
    at=at.path(step.substring(0,index));
    at=at.path(Integer.parseInt(step.substring(index+1,step.length()-1)));
   }else at=at.path(step);
   if(at.isMissingNode()) return null;
  }
  return at.isMissingNode()?null:at;
 }
 private static void set(ObjectNode into,String field,JsonNode value){
  // A field with nothing in it is left out rather than written as null: the far side reads an
  // absent field as "not said", and a null as "said to be nothing".
  if(value!=null&&!value.isNull()) into.set(field,value);
 }

 /** {@code {name}} in a topic becomes what that identity part is for this vehicle. */
 private static String fill(String template,Map<String,String> identity){
  if(template==null) return null;
  var out=new StringBuilder();
  var matcher=Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(template);
  while(matcher.find()){
   var value=identity==null?null:identity.get(matcher.group(1));
   matcher.appendReplacement(out,Matcher.quoteReplacement(value==null?"":value));
  }
  matcher.appendTail(out);
  return out.toString();
 }

 /**
  * Whether this topic is the one the rule describes, and what its {@code {name}} parts were.
  *
  * <p>A {@code +} stands for one level and {@code #} for the rest, as MQTT writes them, so a rule
  * reads like the subscription it belongs to.
  */
 public static Map<String,String> match(String template,String topic){
  var names=new ArrayList<String>();
  var regex=new StringBuilder();
  for(String level:template.split("/",-1)){
   if(!regex.isEmpty()) regex.append("/");
   if(level.equals("#")){ regex.append(".*"); break; }
   if(level.equals("+")){ regex.append("[^/]*"); continue; }
   var matcher=Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(level);
   var part=new StringBuilder();
   int at=0;
   while(matcher.find()){
    part.append(Pattern.quote(level.substring(at,matcher.start()))).append("([^/]+)");
    names.add(matcher.group(1)); at=matcher.end();
   }
   part.append(Pattern.quote(level.substring(at)));
   regex.append(part);
  }
  var matched=Pattern.compile("^"+regex+"$").matcher(topic);
  if(!matched.matches()) return null;
  var captured=new LinkedHashMap<String,String>();
  for(int i=0;i<names.size();i++) captured.put(names.get(i),matched.group(i+1));
  return captured;
 }

 private static Long asLong(JsonNode n){ return n==null||n.isNull()?null:n.asLong(); }
 private static Double asDouble(JsonNode n){ return n==null||n.isNull()?null:n.asDouble(); }
 private static Boolean asBool(JsonNode n){ return n==null||n.isNull()?null:n.asBoolean(); }
 private static String asText(JsonNode n){ return n==null||n.isNull()?null:n.asText(); }
}
