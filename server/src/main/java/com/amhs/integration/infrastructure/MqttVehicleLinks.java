package com.amhs.integration.infrastructure;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.integration.mapping.MessageMapper;
import com.amhs.integration.mapping.VehicleMessageMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vehicle link over MQTT: one connection per broker, and what goes over it written by a mapping
 * document rather than by this class.
 *
 * <p>Everything specific to a vehicle family -- the topics, the field names, the words it uses for
 * being under remote control -- is in the mapping. This holds the connection, decides what a
 * publish is allowed to claim, and writes what comes back to the fleet. That division is what lets
 * a site meet a new maker by writing a document instead of waiting for a release.
 *
 * <p>Delivery is judged the same way as every other channel, because the outbox settles them all
 * the same: no connection is NOT_DELIVERED and may be sent again; a QoS 1 publish the broker has
 * acknowledged is DELIVERED, which is the broker's receipt and not the vehicle's execution; and a
 * publish that was written and not acknowledged is IN_DOUBT and is left for a person. The broker
 * taking it is as far as this can see, and it does not pretend to see further.
 */
@Component
class MqttVehicleLinks implements DeviceChannel {
 private static final Logger log=LoggerFactory.getLogger(MqttVehicleLinks.class);
 private final JdbcTemplate jdbc; private final FleetState fleet; private final MappingLibrary mappings;
 private final ObjectMapper json; private final MessageMapper mapper;
 private final Map<Long,Broker> brokers=new ConcurrentHashMap<>();
 private final int connectTimeoutS,publishTimeoutMs,retryAfterMs;
 private final String clientPrefix;

 MqttVehicleLinks(JdbcTemplate jdbc,FleetState fleet,MappingLibrary mappings,ObjectMapper json,
                  @Value("${amhs.device.mqtt.connect-timeout-s:3}") int connectTimeoutS,
                  @Value("${amhs.device.mqtt.publish-timeout-ms:3000}") int publishTimeoutMs,
                  @Value("${amhs.device.mqtt.retry-after-ms:2000}") int retryAfterMs,
                  @Value("${amhs.device.mqtt.client-prefix:amhs}") String clientPrefix){
  this.jdbc=jdbc; this.fleet=fleet; this.mappings=mappings; this.json=json; this.mapper=new MessageMapper(json);
  this.connectTimeoutS=connectTimeoutS; this.publishTimeoutMs=publishTimeoutMs; this.retryAfterMs=retryAfterMs;
  this.clientPrefix=clientPrefix;
 }

 /** One vehicle on a broker: where it is, what it is called there, and which document speaks for it. */
 private record Wired(long siteId,long profileId,String host,int port,long vehicleId,String mappingCode,
                      Map<String,String> identity){}

 @Override public Verdict send(DeviceCommand command){
  if(command.vehicleId()==null) return Verdict.notDelivered("COMMAND_NAMES_NO_VEHICLE");
  var wired=wiring(command.siteId(),command.vehicleId());
  if(wired==null) return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  var mapping=mappings.byCode(wired.siteId(),wired.mappingCode()).orElse(null);
  // Configuration let the vehicle be switched on against a document that is now unreadable or gone.
  // Nothing was written, so the command waits rather than being counted as lost.
  if(mapping==null) return Verdict.notDelivered("MAPPING_NOT_IN_USE");
  if(!"MQTT_JSON_V1".equals(mapping.adapter())) return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  var message=mapper.toMessage(mapping,view(command,wired)).orElse(null);
  // A command the mapping does not describe is not this vehicle family's to receive. Said rather
  // than dropped: an operator can see that the document is missing a command the controller sends.
  if(message==null) return Verdict.notDelivered("COMMAND_NOT_IN_MAPPING");
  var broker=brokers.compute(wired.profileId(),(id,open)->
   open!=null&&open.reaches(wired.host(),wired.port())?open:replace(open,wired));
  return broker.publish(wired,mapping,message);
 }

 private Broker replace(Broker open,Wired wired){
  if(open!=null) open.close();
  return new Broker(wired.host(),wired.port());
 }

 private MessageMapper.Command view(DeviceCommand command,Wired wired){
  var identity=new LinkedHashMap<>(wired.identity());
  return new MessageMapper.Command(command.commandId(),command.commandType(),command.orderId(),
   wired.vehicleId(),identity.getOrDefault("serial",""),command.attempt(),OffsetDateTime.now().toString(),
   payload(command.payload()),identity);
 }
 private com.fasterxml.jackson.databind.JsonNode payload(String payload){
  try{ return json.readTree(payload==null||payload.isBlank()?"{}":payload); }
  catch(Exception e){ return json.createObjectNode(); }
 }

 /**
  * How this vehicle is wired, or null when it is not. The identity parts are what a mapping's topics
  * are written against: the maker, the name the far side knows it by, the interface the site runs
  * and the topic version of the protocol.
  */
 private Wired wiring(long siteId,long vehicleId){
  return jdbc.query("SELECT c.id,c.host,c.port,p.adapter_key,p.proto_version,v.manufacturer,"
   +"COALESCE(v.serial_number,v.external_vehicle_id),COALESCE(v.interface_name,'uagv')"
   +" FROM integration.vehicle_communication v"
   +" JOIN integration.connection_profile c ON c.site_id=v.site_id AND c.id=v.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
   +" WHERE v.site_id=? AND v.vehicle_id=? AND v.enabled AND c.enabled AND c.comm_type='MQTT' AND c.role='BROKER'",
   (r,n)->new Wired(siteId,r.getLong(1),r.getString(2),r.getInt(3),vehicleId,r.getString(4),
    Map.of("manufacturer",r.getString(6),"serial",r.getString(7),"prefix",r.getString(8),
     "version","v"+r.getString(5).split("\\.")[0])),
   siteId,vehicleId).stream().findFirst().orElse(null);
 }

 private Long vehicleOf(long siteId,long profileId,String externalId){
  return jdbc.query("SELECT vehicle_id FROM integration.vehicle_communication WHERE site_id=? AND connection_profile_id=?"
   +" AND (serial_number=? OR external_vehicle_id=?) AND enabled",(r,n)->r.getLong(1),
   siteId,profileId,externalId,externalId).stream().findFirst().orElse(null);
 }
 private Long activeRevision(long siteId){
  return jdbc.query("SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'",(r,n)->r.getLong(1),siteId)
   .stream().findFirst().orElse(null);
 }
 private Long nodeOf(long siteId,long revisionId,String code){
  return jdbc.query("SELECT id FROM topology.node WHERE site_id=? AND revision_id=? AND code=?",(r,n)->r.getLong(1),
   siteId,revisionId,code).stream().findFirst().orElse(null);
 }

 /** One broker connection, and the vehicles subscribed on it. */
 private final class Broker implements MqttCallback {
  private final String host; private final int port;
  private final Map<String,Listening> listening=new ConcurrentHashMap<>();
  private volatile MqttClient client; private volatile long nextAttemptAt;
  private record Listening(long siteId,long profileId,VehicleMessageMapping mapping){}

  Broker(String host,int port){ this.host=host; this.port=port; }
  boolean reaches(String otherHost,int otherPort){ return host.equals(otherHost)&&port==otherPort; }

  synchronized Verdict publish(Wired wired,VehicleMessageMapping mapping,MessageMapper.Message message){
   var open=connect();
   if(open!=null) return Verdict.notDelivered(open);
   listen(wired,mapping);
   try{
    var payload=json.writeValueAsBytes(message.body());
    var token=client.getTopic(message.topic()).publish(payload,1,false);
    token.waitForCompletion(publishTimeoutMs);
    // The broker has it. What the vehicle does with it is the vehicle's to report.
    return token.isComplete()?Verdict.delivered():Verdict.inDoubt("PUBLISH_NOT_ACKNOWLEDGED");
   }catch(MqttException e){
    return e.getReasonCode()==MqttException.REASON_CODE_CLIENT_NOT_CONNECTED
     ?Verdict.notDelivered("BROKER_NOT_CONNECTED"):Verdict.inDoubt("PUBLISH_FAILED_"+e.getReasonCode());
   }catch(Exception e){ return Verdict.inDoubt(e.getClass().getSimpleName()); }
  }

  /** Null when connected; otherwise why nothing was written. */
  private String connect(){
   if(client!=null&&client.isConnected()) return null;
   if(System.currentTimeMillis()<nextAttemptAt) return "BROKER_DOWN";
   nextAttemptAt=System.currentTimeMillis()+retryAfterMs;
   try{
    var options=new MqttConnectOptions();
    options.setAutomaticReconnect(true); options.setCleanSession(true);
    options.setConnectionTimeout(connectTimeoutS); options.setKeepAliveInterval(30);
    var fresh=new MqttClient("tcp://"+host+":"+port,clientPrefix+"-"+UUID.randomUUID(),new MemoryPersistence());
    fresh.setCallback(this);
    fresh.connect(options);
    client=fresh;
    log.info("broker {}:{} connected",host,port);
    return null;
   }catch(MqttException e){
    log.warn("broker {}:{} refused: {}",host,port,e.getMessage());
    return "BROKER_REFUSED";
   }
  }

  /** Subscribes to what this vehicle's mapping says comes back, once per topic. */
  private void listen(Wired wired,VehicleMessageMapping mapping){
   for(var rule:mapping.inbound()){
    String filter=fill(rule.whenTopic(),wired.identity());
    if(listening.putIfAbsent(filter,new Listening(wired.siteId(),wired.profileId(),mapping))!=null) continue;
    try{ client.subscribe(filter,1); log.info("listening on {}",filter); }
    catch(MqttException e){ listening.remove(filter); log.warn("could not listen on {}: {}",filter,e.getMessage()); }
   }
  }

  @Override public void messageArrived(String topic,MqttMessage message){
   var heard=listening.entrySet().stream().filter(e->MessageMapper.match(e.getKey(),topic)!=null)
    .map(Map.Entry::getValue).findFirst().orElse(null);
   if(heard==null) return;
   try{
    var body=json.readTree(new String(message.getPayload(),StandardCharsets.UTF_8));
    mapper.toReport(heard.mapping(),topic,body).ifPresent(report->write(heard,report));
   }catch(Exception e){ log.warn("unreadable message on {}: {}",topic,e.getMessage()); }
  }

  private void write(Listening heard,MessageMapper.Report report){
   if(!report.saysAnything()) return;
   Long vehicleId=vehicleOf(heard.siteId(),heard.profileId(),report.externalVehicleId());
   Long revisionId=activeRevision(heard.siteId());
   if(vehicleId==null||revisionId==null){
    log.warn("report for vehicle {} on broker {}:{} has nowhere to go",report.externalVehicleId(),host,port);
    return;
   }
   Long nodeId=report.nodeId();
   if(nodeId==null&&report.nodeCode()!=null){
    nodeId=nodeOf(heard.siteId(),revisionId,report.nodeCode());
    // A node this map does not have is not a position. Saying so beats writing the vehicle nowhere.
    if(nodeId==null) log.warn("vehicle {} reports node {}, which this map does not have",vehicleId,report.nodeCode());
   }
   try{
    // Mode and status are only given when the vehicle said them; the report carries the rest as it is.
    if(report.mode()!=null||report.status()!=null||nodeId!=null)
     fleet.report(heard.siteId(),revisionId,vehicleId,new VehicleReport(nodeId,
      report.online()==null||report.online(),"REMOTE".equals(report.mode()),"IDLE".equals(report.status()),
      Boolean.TRUE.equals(report.carrier()),"DEVICE",report.destinationChangeEnabled(),
      report.mode(),report.status()));
    if(report.batteryPct()!=null) fleet.reportBattery(heard.siteId(),revisionId,vehicleId,report.batteryPct());
   }catch(IllegalArgumentException e){
    log.warn("report for vehicle {} refused: {}",vehicleId,e.getMessage());
   }
  }

  @Override public void connectionLost(Throwable cause){
   log.warn("broker {}:{} lost: {}",host,port,cause==null?"":cause.getMessage());
   listening.clear();   // the subscriptions go with the session; they are made again on the next send
  }
  @Override public void deliveryComplete(IMqttDeliveryToken token){ }
  void close(){
   var open=client; client=null; listening.clear();
   if(open!=null) try{ open.disconnectForcibly(200); open.close(); }catch(MqttException ignored){ }
  }
 }

 /** {@code {name}} becomes this vehicle's identity part; a part nobody gave becomes one MQTT level. */
 private static String fill(String template,Map<String,String> identity){
  String out=template;
  for(var part:identity.entrySet()) out=out.replace("{"+part.getKey()+"}",part.getValue());
  return out.replaceAll("\\{[A-Za-z0-9_]+}","+");
 }

 @PreDestroy void closeAll(){ brokers.values().forEach(Broker::close); brokers.clear(); }
}
