package com.amhs.integration.infrastructure;
import com.amhs.fleet.FleetState;
import com.amhs.fleet.VehicleReport;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import com.amhs.integration.mapping.FrameMapper;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.integration.mapping.MessageMapper;
import com.amhs.integration.mapping.SlotCodec;
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
 * a connection profile (TCP, enabled) and, per vehicle, a communication row naming the identifier
 * the far side knows it by.
 *
 * <p>The connection profile's role says which end dials. <b>CLIENT</b> is this controller dialling
 * out to an address it was given. <b>SERVER</b> is a gateway dialling in, which is the ordinary
 * arrangement where the line sits behind a firewall or has no fixed address: a port is opened and
 * whoever arrives must say which vehicle they speak for before anything is sent to them. Both ends
 * speak the same two dialects once the socket exists, and everything after that -- acknowledgement,
 * reports, what a send may claim -- is the same.
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
class JsonLineVehicleLinks implements DeviceChannel,com.amhs.integration.VehicleListening {
 private static final String JSON=com.amhs.integration.DeviceAdapters.JSON_LINE_V1;
 private static final String FRAME=com.amhs.integration.DeviceAdapters.PC_FRAME_V1;
 private static final Logger log=LoggerFactory.getLogger(JsonLineVehicleLinks.class);
 private static final int MAX_FRAME=64*1024;

 private final JdbcTemplate jdbc; private final FleetState fleet; private final MappingLibrary mappings;
 private final ObjectMapper json=new ObjectMapper();
 private final FrameMapper frames=new FrameMapper(json,new MessageMapper(json),new SlotCodec(json));
 /** Links this controller dialled, one per connection profile. */
 private final Map<Long,Link> links=new ConcurrentHashMap<>();
 /** Ports held open for gateways to dial in to, one per connection profile. */
 private final Map<Long,Listener> listeners=new ConcurrentHashMap<>();
 /**
  * Which arrived connection speaks for which vehicle, by the profile it came in on and the name
  * the far side calls that vehicle. A connection may claim several, and a name belongs to one
  * connection at a time.
  */
 private final Map<String,Link> arrived=new ConcurrentHashMap<>();
 private final int connectTimeoutMs,ackTimeoutMs,retryAfterMs,identifyTimeoutMs;
 private final DeviceTls tls;

 JsonLineVehicleLinks(JdbcTemplate jdbc,FleetState fleet,MappingLibrary mappings,DeviceTls tls,
                      @Value("${amhs.device.tcp.connect-timeout-ms:2000}") int connectTimeoutMs,
                      @Value("${amhs.device.tcp.ack-timeout-ms:3000}") int ackTimeoutMs,
                      @Value("${amhs.device.tcp.retry-after-ms:1000}") int retryAfterMs,
                      @Value("${amhs.device.tcp.identify-timeout-ms:10000}") int identifyTimeoutMs){
  this.jdbc=jdbc; this.fleet=fleet; this.mappings=mappings; this.tls=tls;
  this.connectTimeoutMs=connectTimeoutMs; this.ackTimeoutMs=ackTimeoutMs; this.retryAfterMs=retryAfterMs;
  this.identifyTimeoutMs=identifyTimeoutMs;
 }

 /** One vehicle as the far side knows it, and how its connection is made. */
 private record Target(long siteId,long profileId,String host,int port,String role,String externalId,
                       String adapterKey,boolean tls,String peerName){
  /** The document this vehicle is described by, where it is described by one. */
  String mappingCode(){ return adapterKey; }
  /** Whether this controller makes the connection, or waits for it. */
  boolean dialsOut(){ return !"SERVER".equals(role); }
 }
 private static String claimKey(long profileId,String externalId){ return profileId+"/"+externalId; }
 /** An empty name and no name are the same thing: the address dialled, and nothing else. */
 private static String blankToNull(String name){ return name==null||name.isBlank()?null:name; }

 @Override public Verdict send(DeviceCommand command){
  if(command.vehicleId()==null) return Verdict.notDelivered("COMMAND_NAMES_NO_VEHICLE");
  var target=targetOf(command.siteId(),command.vehicleId());
  // Not an error: a vehicle nobody has wired to this adapter is simply not ours to send to, and
  // saying so as "nothing left here" keeps the command repeatable once somebody does wire it.
  if(target==null||dialect(target)==null) return Verdict.notDelivered("ADAPTER_NOT_CONFIGURED");
  Link link;
  if(target.dialsOut())
   // Kept by profile, but only while it is still the address that profile names: an operator who
   // repoints a controller has repointed it, and a link held open to where it used to be would send
   // commands to a machine nobody is talking about any more.
   link=links.compute(target.profileId(),(id,open)->{
    if(open!=null&&open.reaches(target.host(),target.port())&&open.secureAs(target.tls(),target.peerName()))
     return open;
    if(open!=null) open.close();
    var fresh=new Link(target.host(),target.port(),FRAME.equals(dialect(target)),true,
     target.tls(),target.peerName());
    fresh.mappingCode=target.mappingCode();
    return fresh;
   });
  else{
   link=arrived.get(claimKey(target.profileId(),target.externalId()));
   // Nobody has dialled in for this vehicle, so nothing was written and nothing can have reached
   // it. Sending it again once the gateway arrives cannot duplicate anything.
   if(link==null) return Verdict.notDelivered("VEHICLE_NOT_CONNECTED");
  }
  return link.send(target,command);
 }

 /**
  * Which of this adapter's two dialects the vehicle speaks, or null when it speaks neither.
  *
  * <p>JSON_LINE_V1 is built in and needs no document. Anything else is the name of a mapping
  * document, and it is this adapter's only if that document says so -- the same key on a broker
  * or a PLC belongs to another adapter, and answering for it would take the command away from the
  * one that can actually send it.
  */
 private String dialect(Target target){
  if(JSON.equals(target.adapterKey())) return JSON;
  var mapping=mappings.byCode(target.siteId(),target.adapterKey()).orElse(null);
  return mapping!=null&&FRAME.equals(mapping.adapter())?FRAME:null;
 }

 /**
  * The vehicle's link, or null when it has none in use. Read per send rather than cached: the
  * configuration is what an operator just changed, and a command sent to yesterday's address is
  * worse than a command delayed by one query.
  */
 private Target targetOf(long siteId,long vehicleId){
  return jdbc.query("SELECT c.id,c.host,c.port,c.role,v.external_vehicle_id,p.adapter_key,c.tls,c.peer_name"
   +" FROM integration.vehicle_communication v"
   +" JOIN integration.connection_profile c ON c.site_id=v.site_id AND c.id=v.connection_profile_id"
   +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
   +" WHERE v.site_id=? AND v.vehicle_id=? AND v.enabled AND c.enabled AND c.comm_type='TCP'"
   +" AND c.role IN ('CLIENT','SERVER')",
   (r,n)->new Target(siteId,r.getLong(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),
    r.getString(6),r.getBoolean(7),r.getString(8)),
   siteId,vehicleId).stream().findFirst().orElse(null);
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
  private final String host; private final int port; private final boolean framed; private final boolean dialsOut;
  /** Whether this link is TLS, and the name the far side's certificate has to carry. */
  private final boolean secure; private final String peerName;
  private volatile String mappingCode;
  private final ReentrantLock write=new ReentrantLock();
  private final Map<String,CompletableFuture<Void>> awaiting=new ConcurrentHashMap<>();
  /** The names this connection has said it speaks for, so dropping it gives every one of them up. */
  private final java.util.Set<String> claims=ConcurrentHashMap.newKeySet();
  private volatile Socket socket; private volatile OutputStream out; private volatile Thread reader;
  private volatile long nextAttemptAt;

  Link(String host,int port,boolean framed,boolean dialsOut,boolean secure,String peerName){
   this.host=host; this.port=port; this.framed=framed; this.dialsOut=dialsOut;
   this.secure=secure; this.peerName=peerName;
  }
  boolean reaches(String otherHost,int otherPort){ return host.equals(otherHost)&&port==otherPort; }
  /**
   * Whether an open link is still the link the configuration now describes.
   *
   * <p>Asked beside the address, and for the same reason: an operator who has just turned TLS on
   * meant the next command to go down a secured link, not down the plaintext one that was already
   * open.
   */
  boolean secureAs(boolean otherSecure,String otherPeer){
   return secure==otherSecure&&java.util.Objects.equals(blankToNull(peerName),blankToNull(otherPeer));
  }

  /**
   * Takes over a connection somebody made to us and starts reading it.
   *
   * <p>It speaks for nothing yet. Until a frame names a vehicle this site uses, there is no vehicle
   * whose commands may go down it -- a socket that has not said who it is is just a socket.
   */
  void adopt(long siteId,long profileId,Socket s) throws IOException {
   s.setTcpNoDelay(true);
   // Say who you are, or go. A connection that never identifies itself cannot be sent to and
   // cannot be accounted for, and holding it open only hides that nothing is connected.
   s.setSoTimeout(identifyTimeoutMs);
   socket=s; out=s.getOutputStream();
   reader=Thread.ofVirtual().name("vehicle-dial-in-"+host+":"+port)
    .start(()->read(siteId,profileId,s));
  }

  /**
   * The far side has named a vehicle, so this connection is that vehicle's until it goes.
   *
   * <p>A second connection naming a vehicle the first one claimed takes it: a gateway that
   * restarted is on the new socket, and the old one is a line nobody is at the other end of.
   * Refusing the new one would leave that vehicle unreachable for as long as the dead socket
   * stays open, which is the wrong way round.
   */
  private void claim(long siteId,long profileId,String externalId){
   if(dialsOut||externalId==null||externalId.isBlank()) return;
   var key=claimKey(profileId,externalId);
   if(claims.contains(key)) return;
   if(vehicleOf(siteId,profileId,externalId)==null){
    // Not a vehicle of this profile. Nothing it says may be written against a row, and a
    // connection that cannot be placed is not one to keep.
    log.warn("dial-in on {}:{} names vehicle {}, which this profile does not use",host,port,externalId);
    drop("named a vehicle this profile does not use");
    return;
   }
   claims.add(key);
   var previous=arrived.put(key,this);
   if(previous!=null&&previous!=this){
    previous.claims.remove(key);
    log.info("vehicle {} is now on the connection from {}:{}",externalId,host,port);
    if(previous.claims.isEmpty()) previous.drop("another connection speaks for its vehicles now");
   }
   try{ if(socket!=null) socket.setSoTimeout(0); }
   catch(IOException ignored){ /* identified already; the timeout only mattered before that */ }
  }

  Verdict send(Target target,DeviceCommand command){
   byte[] line; String awaitKey=command.commandId();
   if(framed){
    // A byte protocol has no room for a command id, so what comes back is matched on the sequence
    // the frame carried. The outbox's attempt number is that sequence: it is already the thing
    // that tells one attempt from another.
    var mapping=mappings.byCode(target.siteId(),target.mappingCode()).orElse(null);
    if(mapping==null) return Verdict.notDelivered("MAPPING_NOT_IN_USE");
    try{
     var built=frames.toFrame(mapping,view(target,command));
     if(built.isEmpty()) return Verdict.notDelivered("COMMAND_NOT_IN_MAPPING");
     line=built.get();
    }catch(SlotCodec.SlotRejected e){
     log.warn("command {} does not fit the frame: {}",command.commandId(),e.getMessage());
     return Verdict.notDelivered("COMMAND_DOES_NOT_FIT");
    }
    awaitKey=String.valueOf(command.attempt());
   }else{
    var frame=json.createObjectNode();
    frame.put("type","command"); frame.put("commandId",command.commandId()); frame.put("vehicle",target.externalId());
    frame.put("command",command.commandType()); frame.put("sentAt",OffsetDateTime.now().toString());
    // The payload is the decision's own shape; it is carried through rather than re-interpreted here,
    // so an adapter that maps it to a vehicle's protocol has everything the decision had.
    try{ frame.set("payload",json.readTree(command.payload()==null?"{}":command.payload())); }
    catch(IOException e){ frame.put("payload",command.payload()); }
    try{ line=(json.writeValueAsString(frame)+"\n").getBytes(StandardCharsets.UTF_8); }
    catch(IOException e){ return Verdict.notDelivered("PAYLOAD_NOT_WRITABLE"); }
   }
   if(line.length>MAX_FRAME) return Verdict.notDelivered("FRAME_TOO_LONG");

   var ack=new CompletableFuture<Void>();
   final String waitingFor=awaitKey;
   awaiting.put(waitingFor,ack);
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
   }finally{ awaiting.remove(waitingFor); }
  }

  /** The command as a mapping sees it. */
  private MessageMapper.Command view(Target target,DeviceCommand command){
   JsonNode payload;
   try{ payload=json.readTree(command.payload()==null||command.payload().isBlank()?"{}":command.payload()); }
   catch(IOException e){ payload=json.createObjectNode(); }
   return new MessageMapper.Command(command.commandId(),command.commandType(),command.orderId(),
    command.vehicleId()==null?0:command.vehicleId(),target.externalId(),command.attempt(),
    OffsetDateTime.now().toString(),payload,java.util.Map.of());
  }

  /** Null when the link is open; otherwise why it is not, as a reason nothing was written. */
  private String connect(Target target){
   if(socket!=null&&socket.isConnected()&&!socket.isClosed()) return null;
   // Nothing to dial: this connection was made by the far side, and it has gone.
   if(!dialsOut) return "VEHICLE_NOT_CONNECTED";
   if(System.currentTimeMillis()<nextAttemptAt) return "LINK_DOWN";
   nextAttemptAt=System.currentTimeMillis()+retryAfterMs;
   try{
    Socket s;
    if(secure){
     // The handshake is part of connecting. A certificate that is not trusted, or is for another
     // machine, fails here -- before a byte of any command has been written, which is why this is
     // a plain "nothing was written" rather than anything in doubt.
     s=tls.connect(host,port,connectTimeoutMs,peerName);
     log.info("vehicle link to {}:{} open to {} over TLS",host,port,
      DeviceTls.peerName((javax.net.ssl.SSLSocket)s));
    }else{
     s=new Socket();
     s.connect(new InetSocketAddress(host,port),connectTimeoutMs);
     log.info("vehicle link to {}:{} open",host,port);
    }
    s.setTcpNoDelay(true);
    socket=s; out=s.getOutputStream();
    final Socket opened=s;
    reader=Thread.ofVirtual().name("vehicle-link-"+host+":"+port)
     .start(()->read(target.siteId(),target.profileId(),opened));
    return null;
   }catch(DeviceTls.TlsUnavailable e){
    // The site asked for a secured link and the server has nothing to secure it with. Nothing is
    // written, and nothing is sent in plaintext instead -- a quiet downgrade is the one outcome
    // nothing above the link could notice.
    log.error("vehicle link to {}:{} asks for TLS: {}",host,port,e.getMessage());
    return e.getMessage();
   }catch(IOException e){
    log.warn("vehicle link to {}:{} refused: {}",host,port,e.getMessage());
    return "LINK_REFUSED";
   }
  }

  private void read(long siteId,long profileId,Socket s){
   if(framed){ readFrames(siteId,profileId,s); return; }
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

  /**
   * Frames of a fixed size, read whole or not at all.
   *
   * <p>A byte protocol has no way of saying "the rest follows", so a frame is read to its last
   * byte before anything is made of it. A short read at the end of a link is not a short frame; it
   * is the link ending, and the frame it was carrying is simply not there.
   */
  private void readFrames(long siteId,long profileId,Socket s){
   var mapping=mappings.byCode(siteId,mappingCode).orElse(null);
   int size=mapping==null||mapping.frame()==null||mapping.frame().size()==null?0:mapping.frame().size();
   if(size<=0){ drop("the mapping does not say how long a frame is"); return; }
   var buffer=new byte[size];
   try(var in=new java.io.DataInputStream(s.getInputStream())){
    while(true){
     in.readFully(buffer);
     try{
      var decoded=frames.fromFrame(mapping,buffer.clone());
      decoded.ifPresent(said->{
       // The slot the document calls the identity is how a dialled-in gateway says which vehicle
       // this frame is about, and so which vehicle this connection speaks for.
       claim(siteId,profileId,said.externalId());
       if(said.ackOf()!=null){
        var waiting=awaiting.get(said.ackOf());
        if(waiting!=null) waiting.complete(null);
       }
       if(said.report().saysAnything()) writeReport(siteId,profileId,said);
      });
     }catch(RuntimeException e){
      // A frame that does not add up is dropped and said. Reading it anyway is how a corrupted
      // node number becomes a vehicle driving somewhere nobody asked for.
      log.warn("frame from {}:{} refused: {}",host,port,e.getMessage());
     }
    }
   }catch(IOException e){ drop("read ended: "+e.getMessage()); }
  }

  private void writeReport(long siteId,long profileId,FrameMapper.Decoded said){
   Long vehicleId=vehicleOf(siteId,profileId,said.externalId());
   Long revisionId=activeRevision(siteId);
   if(vehicleId==null||revisionId==null){
    log.warn("frame report for {} on {}:{} has nowhere to go",said.externalId(),host,port);
    return;
   }
   var report=said.report();
   try{
    if(report.mode()!=null||report.status()!=null||report.nodeId()!=null)
     fleet.report(siteId,revisionId,vehicleId,new VehicleReport(report.nodeId(),
      report.online()==null||report.online(),"REMOTE".equals(report.mode()),"IDLE".equals(report.status()),
      Boolean.TRUE.equals(report.carrier()),"DEVICE",report.destinationChangeEnabled(),
      report.mode(),report.status()));
    if(report.batteryPct()!=null) fleet.reportBattery(siteId,revisionId,vehicleId,report.batteryPct());
   }catch(IllegalArgumentException e){
    log.warn("frame report for vehicle {} refused: {}",vehicleId,e.getMessage());
   }
  }

  private void handle(long siteId,long profileId,JsonNode frame) throws IOException {
   String type=frame.path("type").asText("");
   // Every frame of this contract that is about a vehicle names it, so a dialled-in gateway says
   // which vehicles it speaks for simply by speaking about them. `hello` is the frame for saying
   // so with nothing else to report, which is what a gateway sends when it has just connected.
   if(frame.hasNonNull("vehicle")) claim(siteId,profileId,frame.get("vehicle").asText());
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
   // Give up the vehicles this connection spoke for, but only where it is still the one on record:
   // a connection replaced by a newer one must not take that vehicle away from its successor.
   claims.forEach(key->arrived.remove(key,this));
   claims.clear();
   if(s!=null){ log.warn("vehicle link to {}:{} closed: {}",host,port,why); try{ s.close(); }catch(IOException ignored){ } }
   awaiting.values().forEach(f->f.completeExceptionally(new IOException(why)));
  }
  void close(){ drop("server stopping"); }
 }

 /**
  * A port held open for gateways to dial in to, one per connection profile.
  *
  * <p>One listener speaks one dialect, because a frame arriving on it has to be read as something
  * before anything about it can be known. Where the vehicles behind a profile do not agree on the
  * dialect, the port is not opened and the disagreement is said: guessing per connection would mean
  * reading a vehicle's position out of bytes that are not a position.
  */
 private final class Listener {
  private final long siteId,profileId; private final String bind; private final int port;
  private final boolean framed; private final String mappingCode;
  /** Whether arriving here means proving who you are, and the name that must be proved. */
  private final boolean secure; private final String peerName;
  private volatile java.net.ServerSocket server; private volatile Thread accepting;
  private final java.util.List<Link> accepted=new java.util.concurrent.CopyOnWriteArrayList<>();

  Listener(long siteId,long profileId,String bind,int port,boolean framed,String mappingCode,
           boolean secure,String peerName){
   this.siteId=siteId; this.profileId=profileId; this.bind=bind; this.port=port;
   this.framed=framed; this.mappingCode=mappingCode; this.secure=secure; this.peerName=peerName;
  }
  boolean matches(String otherBind,int otherPort,boolean otherFramed,String otherMapping,
                  boolean otherSecure,String otherPeer){
   return bind.equals(otherBind)&&port==otherPort&&framed==otherFramed
    &&java.util.Objects.equals(mappingCode,otherMapping)
    &&secure==otherSecure&&java.util.Objects.equals(blankToNull(peerName),blankToNull(otherPeer));
  }

  /** Opens the port. Returns false when it could not be had, which is said and then left alone. */
  boolean open(){
   try{
    java.net.ServerSocket s;
    if(secure){
     s=tls.listen(bind,port);
     log.info("listening for vehicle connections on {}:{} ({}) over TLS; a certificate is required{}",
      bind,port,framed?FRAME:JSON,peerName==null||peerName.isBlank()?"":" and must say "+peerName);
    }else{
     s=new java.net.ServerSocket();
     s.setReuseAddress(true);
     s.bind(new InetSocketAddress(bind,port));
     log.info("listening for vehicle connections on {}:{} ({})",bind,port,framed?FRAME:JSON);
    }
    server=s;
    accepting=Thread.ofVirtual().name("vehicle-listener-"+bind+":"+port).start(this::accept);
    return true;
   }catch(DeviceTls.TlsUnavailable e){
    // The port is not opened in plaintext instead. A gateway that could not connect is a problem
    // somebody will fix; a gateway that connected to an unprotected port is one nobody will.
    log.error("not listening on {}:{}: this profile asks for TLS and {}",bind,port,e.getMessage());
    return false;
   }catch(IOException e){
    log.error("cannot listen for vehicles on {}:{}: {}",bind,port,e.getMessage());
    return false;
   }
  }

  private void accept(){
   var s=server;
   while(s!=null&&!s.isClosed()){
    try{
     var socket=s.accept();
     // Each arrival is taken up on its own thread: the handshake waits on the far side, and one
     // gateway that connects and then says nothing must not hold the door shut for the rest.
     Thread.ofVirtual().name("vehicle-arrival-"+socket.getInetAddress().getHostAddress()).start(()->welcome(socket));
    }catch(IOException e){
     if(server!=null&&!server.isClosed()) log.warn("accept on {}:{} failed: {}",bind,port,e.getMessage());
     return;
    }
   }
  }

  /**
   * Finds out who arrived, and then lets them speak.
   *
   * <p>Two questions, in this order, and they are different questions. <b>Who are you</b> is the
   * certificate: signed by an authority this site trusts, and carrying the name this profile
   * expects. <b>What may you speak for</b> is the vehicle the connection then claims, which has to
   * be one wired to this profile. A gateway with a good certificate still cannot claim a vehicle
   * that is not its own, and a gateway that knows a vehicle's name -- which is written in the map
   * file and on every screen -- cannot do anything with it without the certificate.
   */
  private void welcome(Socket socket){
   String from=socket.getInetAddress().getHostAddress();
   try{
    if(secure){
     var ssl=(javax.net.ssl.SSLSocket)socket;
     // Fails here when there is no trusted certificate -- and gives up when the far side simply
     // never answers, which would otherwise hold this arrival's thread open for ever.
     DeviceTls.handshake(ssl,identifyTimeoutMs);
     if(!DeviceTls.presents(ssl,peerName)){
      log.warn("connection from {} presents {} and this profile expects {}; closed",
       from,DeviceTls.peerName(ssl),peerName);
      socket.close();
      return;
     }
     log.info("vehicle connection from {} on profile {}, certified as {}",from,profileId,
      DeviceTls.peerName(ssl));
    }else log.info("vehicle connection from {}:{} on profile {}",from,socket.getPort(),profileId);
    var link=new Link(from,socket.getPort(),framed,false,secure,peerName);
    link.mappingCode=mappingCode;
    accepted.add(link);
    link.adopt(siteId,profileId,socket);
   }catch(IOException e){
    log.warn("connection from {} was not taken up: {}",from,e.getMessage());
    try{ socket.close(); }catch(IOException ignored){ }
   }
  }

  /** Closes the port and everything that came in on it. */
  void close(){
   var s=server; server=null;
   if(s!=null){ try{ s.close(); }catch(IOException ignored){ } }
   accepted.forEach(Link::close); accepted.clear();
   log.info("stopped listening on {}:{}",bind,port);
  }
  /** Forgets connections that have gone, so a long-running listener does not accumulate them. */
  void sweep(){ accepted.removeIf(link->link.socket==null); }
 }

 /**
  * Brings the open ports in line with the configuration, which is how a change to it takes effect.
  *
  * <p>Read rather than remembered: opening a port is the one thing here that an operator cannot
  * undo by editing a row, so the rows are what decides, every time.
  */
 @Override
 @org.springframework.scheduling.annotation.Scheduled(
  fixedDelayString="${amhs.device.tcp.listen-refresh-ms:5000}")
 public void listen(){
  var wanted=new java.util.HashMap<Long,Listener>();
  try{
   jdbc.query("SELECT c.id,c.site_id,c.host,c.port,min(p.adapter_key) AS one,max(p.adapter_key) AS other,"
    +"c.tls,c.peer_name"
    +" FROM integration.connection_profile c"
    +" JOIN integration.vehicle_communication v ON v.site_id=c.site_id AND v.connection_profile_id=c.id AND v.enabled"
    +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
    +" WHERE c.enabled AND c.comm_type='TCP' AND c.role='SERVER'"
    +" GROUP BY c.id,c.site_id,c.host,c.port,c.tls,c.peer_name",(r,n)->{
     long profileId=r.getLong(1),siteId=r.getLong(2);
     String one=r.getString(5),other=r.getString(6);
     if(!one.equals(other)){
      log.error("vehicles on connection profile {} do not agree on a dialect ({} and {}); not listening",
       profileId,one,other);
      return null;
     }
     String spoken=JSON.equals(one)?JSON
      :mappings.byCode(siteId,one).filter(m->FRAME.equals(m.adapter())).map(m->FRAME).orElse(null);
     if(spoken==null) return null;     // another adapter's key, or a document nobody could read
     wanted.put(profileId,new Listener(siteId,profileId,r.getString(3),r.getInt(4),FRAME.equals(spoken),one,
      r.getBoolean("tls"),r.getString("peer_name")));
     return null;
    });
  }catch(RuntimeException e){
   // The database is not answering. Keeping what is open is the safer half of being wrong: a
   // gateway stays connected, where closing would drop vehicles over a query that failed once.
   log.warn("could not read which ports to listen on: {}",e.getMessage());
   return;
  }
  listeners.entrySet().removeIf(open->{
   var want=wanted.get(open.getKey());
   if(want!=null&&open.getValue().matches(want.bind,want.port,want.framed,want.mappingCode,
    want.secure,want.peerName)) return false;
   open.getValue().close();
   return true;
  });
  // A port that could not be had puts nothing in the map, so the next sweep tries it again --
  // which is what somebody freeing that port expects to happen.
  wanted.forEach((profileId,want)->listeners.computeIfAbsent(profileId,id->want.open()?want:null));
  listeners.values().forEach(Listener::sweep);
 }

 @PreDestroy void closeAll(){
  links.values().forEach(Link::close); links.clear();
  listeners.values().forEach(Listener::close); listeners.clear();
  arrived.clear();
 }

}
