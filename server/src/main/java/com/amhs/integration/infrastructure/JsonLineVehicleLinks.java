package com.amhs.integration.infrastructure;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The link to real vehicles: one TCP connection per configured controller, one JSON object per line.
 *
 * <p>Until now a site in OPERATION had nowhere to send to, and every command came back
 * ADAPTER_NOT_IMPLEMENTED. This is the first adapter that answers. It speaks this system's own
 * contract ({@code docs/vehicle-tcp-contract.md}) rather than any one vehicle's protocol: a
 * controller or gateway in front of a line translates. Which vehicles use it is configuration --
 * a connection profile (TCP, CLIENT, enabled) and, per vehicle, a communication row naming the
 * identifier the far side knows it by.
 *
 * <p>What a send is allowed to claim is the whole of the contract the gateway settles on:
 * <ul>
 * <li>No link, or the connection refused: <b>NOT_DELIVERED</b>. Nothing was written, so sending it
 *     again cannot duplicate anything at the vehicle.</li>
 * <li>Written and acknowledged by its command id within the ack timeout: <b>DELIVERED</b>. That is
 *     receipt, never execution.</li>
 * <li>Written and not acknowledged, or the link broke after any byte went out: <b>IN_DOUBT</b>. The
 *     vehicle may be acting on it; nothing here may decide otherwise.</li>
 * </ul>
 *
 * <p>Inbound, the far side reports where its vehicles are and acknowledges what it received. A
 * report is written to the fleet exactly like a simulated one, so everything above this -- stale
 * position, traffic, dispatch -- behaves the same whether the vehicle is real or simulated.
 */
@Component
class JsonLineVehicleLinks implements DeviceChannel {
 private static final String ADAPTER_KEY=com.amhs.integration.VehicleAdapters.JSON_LINE_V1;
 private static final Logger log=LoggerFactory.getLogger(JsonLineVehicleLinks.class);
 private static final int MAX_FRAME=64*1024;

 private final JdbcTemplate jdbc; private final FleetState fleet;
 private final ObjectMapper json=new ObjectMapper();
 private final Map<Long,Link> links=new ConcurrentHashMap<>();
 private final int connectTimeoutMs,ackTimeoutMs,retryAfterMs;

 JsonLineVehicleLinks(JdbcTemplate jdbc,FleetState fleet,
                      @Value("${amhs.device.tcp.connect-timeout-ms:2000}") int connectTimeoutMs,
                      @Value("${amhs.device.tcp.ack-timeout-ms:3000}") int ackTimeoutMs,
                      @Value("${amhs.device.tcp.retry-after-ms:1000}") int retryAfterMs){
  this.jdbc=jdbc; this.fleet=fleet;
  this.connectTimeoutMs=connectTimeoutMs; this.ackTimeoutMs=ackTimeoutMs; this.retryAfterMs=retryAfterMs;
 }

 /** One vehicle as the far side knows it, and where to reach it. */
 private record Target(long siteId,long profileId,String host,int port,String externalId){}

 @Override public Verdict send(DeviceCommand command){
  if(command.vehicleId()==null) return Verdict.notDelivered("COMMAND_NAMES_NO_VEHICLE");
  var target=targetOf(command.siteId(),command.vehicleId());
  // Not an error: a vehicle nobody has wired to this adapter is simply not ours to send to, and
  // saying so as "nothing left here" keeps the command repeatable once somebody does wire it.
  if(target==null) return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  // Kept by profile, but only while it is still the address that profile names: an operator who
  // repoints a controller has repointed it, and a link held open to where it used to be would send
  // commands to a machine nobody is talking about any more.
  var link=links.compute(target.profileId(),(id,open)->{
   if(open!=null&&open.reaches(target.host(),target.port())) return open;
   if(open!=null) open.close();
   return new Link(target.host(),target.port());
  });
  return link.send(target,command);
 }

 /**
  * The vehicle's link, or null when it has none in use. Read per send rather than cached: the
  * configuration is what an operator just changed, and a command sent to yesterday's address is
  * worse than a command delayed by one query.
  */
 private Target targetOf(long siteId,long vehicleId){
  return jdbc.query("SELECT c.id,c.host,c.port,v.external_vehicle_id FROM integration.vehicle_communication v"
   +" JOIN integration.connection_profile c ON c.site_id=v.site_id AND c.id=v.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
   +" WHERE v.site_id=? AND v.vehicle_id=? AND v.enabled AND c.enabled AND c.comm_type='TCP' AND c.role='CLIENT'"
   +" AND p.adapter_key=?",
   (r,n)->new Target(siteId,r.getLong(1),r.getString(2),r.getInt(3),r.getString(4)),
   siteId,vehicleId,ADAPTER_KEY).stream().findFirst().orElse(null);
 }

 /** Which vehicle of this site the far side means by that identifier, or null. */
 private Long vehicleOf(long siteId,long profileId,String externalId){
  return jdbc.query("SELECT vehicle_id FROM integration.vehicle_communication WHERE site_id=? AND connection_profile_id=?"
   +" AND external_vehicle_id=? AND enabled",(r,n)->r.getLong(1),siteId,profileId,externalId).stream().findFirst().orElse(null);
 }
 private Long activeRevision(long siteId){
  return jdbc.query("SELECT id FROM topology.map_revision WHERE site_id=? AND status='ACTIVE'",(r,n)->r.getLong(1),siteId)
   .stream().findFirst().orElse(null);
 }

 /**
  * One connection, with the thread that reads it.
  *
  * <p>Opened when there is something to send and not before: a site that is configured but not yet
  * cabled should not fill a log with connection attempts nobody asked for. A connection that fails
  * is not retried until {@code retry-after-ms} has passed, so a run of commands to a dead line
  * costs one attempt rather than one per command.
  */
 private final class Link {
  private final String host; private final int port;
  private final ReentrantLock write=new ReentrantLock();
  private final Map<String,CompletableFuture<Void>> awaiting=new ConcurrentHashMap<>();
  private volatile Socket socket; private volatile OutputStream out; private volatile Thread reader;
  private volatile long nextAttemptAt;

  Link(String host,int port){ this.host=host; this.port=port; }
  boolean reaches(String otherHost,int otherPort){ return host.equals(otherHost)&&port==otherPort; }

  Verdict send(Target target,DeviceCommand command){
   var frame=json.createObjectNode();
   frame.put("type","command"); frame.put("commandId",command.commandId()); frame.put("vehicle",target.externalId());
   frame.put("command",command.commandType()); frame.put("sentAt",OffsetDateTime.now().toString());
   // The payload is the decision's own shape; it is carried through rather than re-interpreted here,
   // so an adapter that maps it to a vehicle's protocol has everything the decision had.
   try{ frame.set("payload",json.readTree(command.payload()==null?"{}":command.payload())); }
   catch(IOException e){ frame.put("payload",command.payload()); }
   byte[] line;
   try{ line=(json.writeValueAsString(frame)+"\n").getBytes(StandardCharsets.UTF_8); }
   catch(IOException e){ return Verdict.notDelivered("PAYLOAD_NOT_WRITABLE"); }
   if(line.length>MAX_FRAME) return Verdict.notDelivered("FRAME_TOO_LONG");

   var ack=new CompletableFuture<Void>();
   awaiting.put(command.commandId(),ack);
   try{
    write.lock();
    try{
     var open=connect(target);
     if(open!=null) return Verdict.notDelivered(open);     // nothing was written
     out.write(line); out.flush();
    }catch(IOException e){
     // Part of it may be on the wire. Nobody can say how much, so nobody may say it did not arrive.
     drop("write failed: "+e.getMessage());
     return Verdict.inDoubt("WRITE_FAILED");
    }finally{ write.unlock(); }
    try{ ack.get(ackTimeoutMs,TimeUnit.MILLISECONDS); return Verdict.delivered(); }
    catch(TimeoutException e){ return Verdict.inDoubt("ACK_TIMEOUT"); }
    catch(ExecutionException e){ return Verdict.inDoubt("LINK_LOST_AFTER_WRITE"); }
    catch(InterruptedException e){ Thread.currentThread().interrupt(); return Verdict.inDoubt("INTERRUPTED"); }
   }finally{ awaiting.remove(command.commandId()); }
  }

  /** Null when the link is open; otherwise why it is not, as a reason nothing was written. */
  private String connect(Target target){
   if(socket!=null&&socket.isConnected()&&!socket.isClosed()) return null;
   if(System.currentTimeMillis()<nextAttemptAt) return "LINK_DOWN";
   nextAttemptAt=System.currentTimeMillis()+retryAfterMs;
   try{
    var s=new Socket();
    s.connect(new InetSocketAddress(host,port),connectTimeoutMs);
    s.setTcpNoDelay(true);
    socket=s; out=s.getOutputStream();
    reader=Thread.ofVirtual().name("vehicle-link-"+host+":"+port).start(()->read(target.siteId(),target.profileId(),s));
    log.info("vehicle link to {}:{} open",host,port);
    return null;
   }catch(IOException e){
    log.warn("vehicle link to {}:{} refused: {}",host,port,e.getMessage());
    return "LINK_REFUSED";
   }
  }

  private void read(long siteId,long profileId,Socket s){
   try(var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8),MAX_FRAME)){
    String line;
    while((line=in.readLine())!=null){
     if(line.isBlank()) continue;
     try{ handle(siteId,profileId,json.readTree(line)); }
     catch(RuntimeException|IOException e){ log.warn("unreadable frame from {}:{}: {}",host,port,e.getMessage()); }
    }
    drop("far side closed the link");
   }catch(IOException e){ drop("read failed: "+e.getMessage()); }
  }

  private void handle(long siteId,long profileId,JsonNode frame) throws IOException {
   String type=frame.path("type").asText("");
   switch(type){
    case "ack" -> {
     var waiting=awaiting.get(frame.path("commandId").asText(""));
     if(waiting!=null) waiting.complete(null);
    }
    case "report" -> report(siteId,profileId,frame);
    // A device telling us what it finished is a transfer report, which belongs to the order and not
    // to the link; it comes in through the transitions endpoint, where it is judged as evidence.
    case "ping" -> pong();
    case "pong","hello" -> { }
    default -> log.debug("frame type {} from {}:{} ignored",type,host,port);
   }
  }

  private void pong() throws IOException {
   write.lock();
   try{ if(out!=null){ out.write("{\"type\":\"pong\"}\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } }
   finally{ write.unlock(); }
  }

  private void report(long siteId,long profileId,JsonNode frame){
   Long vehicleId=vehicleOf(siteId,profileId,frame.path("vehicle").asText(""));
   Long revisionId=activeRevision(siteId);
   // A report naming a vehicle this site does not use, or arriving before a map is in use, is not
   // something to guess about: it is dropped and said, rather than written against the wrong row.
   if(vehicleId==null||revisionId==null){
    log.warn("report from {}:{} for unknown vehicle {}",host,port,frame.path("vehicle").asText(""));
    return;
   }
   Long nodeId=frame.hasNonNull("nodeId")?frame.get("nodeId").asLong():null;
   String mode=frame.hasNonNull("mode")?frame.get("mode").asText():null;
   String status=frame.hasNonNull("status")?frame.get("status").asText():null;
   try{
    fleet.report(siteId,revisionId,vehicleId,new VehicleReport(nodeId,true,"REMOTE".equals(mode),"IDLE".equals(status),
     frame.path("carrier").asBoolean(false),"DEVICE",
     frame.hasNonNull("destinationChangeEnabled")?frame.get("destinationChangeEnabled").asBoolean():null,mode,status));
    if(frame.hasNonNull("batteryPct")) fleet.reportBattery(siteId,revisionId,vehicleId,frame.get("batteryPct").asDouble());
   }catch(IllegalArgumentException e){
    // A mode or status this system does not know is not forced into one it does: the report is
    // refused and said, so a vehicle reporting nonsense is visible rather than quietly idle.
    log.warn("report from {}:{} for vehicle {} refused: {}",host,port,vehicleId,e.getMessage());
   }
  }

  /** Closes the link and tells everything waiting that it may not assume anything. */
  private void drop(String why){
   var s=socket;
   socket=null; out=null;
   if(s!=null){ log.warn("vehicle link to {}:{} closed: {}",host,port,why); try{ s.close(); }catch(IOException ignored){ } }
   awaiting.values().forEach(f->f.completeExceptionally(new IOException(why)));
  }
  void close(){ drop("server stopping"); }
 }

 @PreDestroy void closeAll(){ links.values().forEach(Link::close); links.clear(); }

}
