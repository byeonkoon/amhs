package com.amhs.mapio;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;
import com.amhs.platform.ConfigurationLock;
import com.amhs.platform.VehicleType;
import com.amhs.topology.TopologyConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads a whole map in from a file, and writes one out.
 *
 * <p>A map written a node at a time through the screens is fine for changing a junction and useless
 * for laying out a plant: a hundred nodes entered by hand is an afternoon of typing and a dozen
 * mistakes nobody can see. So a map arrives as one document, is checked as a whole, and becomes a
 * revision of its own.
 *
 * <p><b>It never touches the map in use.</b> Importing creates a new revision and leaves it in
 * draft; putting it into use is a separate decision, with its own rules about open work. That is
 * also why importing does not need the site stopped -- a map can be drawn while the plant runs,
 * and only the switch has to wait for a quiet moment.
 *
 * <p>Everything in the document refers to everything else by code. The ids are made here and are
 * nobody else's business: a file carrying ids would mean something different in another database,
 * which is exactly the kind of difference nobody notices until a vehicle is sent somewhere.
 */
@Service
public class MapImport {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final TopologyConfiguration topology;
 public MapImport(JdbcTemplate jdbc,ConfigurationLock guard,TopologyConfiguration topology){
  this.jdbc=jdbc; this.guard=guard; this.topology=topology;
 }

 /**
  * Writes the document in as a new draft revision and returns it.
  *
  * <p>Checked before anything is written: every code is present and used once, every reference
  * names something in this same document, and every port and vehicle agrees with the kind of
  * network it sits on. A map that half-imported would be worse than one rejected -- it would look
  * like a map.
  */
 @Transactional
 public TopologyConfiguration.RevisionView importDocument(long site,MapDocument document){
  guard.requireSite(site);
  ConfigurationException.require(!document.nodes().isEmpty(),"MAP_HAS_NO_TRACK");

  // ---- the track ----
  var nodeIds=new HashMap<String,Long>();
  for(var node:document.nodes()){
   String code=text(node.code(),"NODE_CODE");
   ConfigurationException.require(nodeIds.putIfAbsent(code,0L)==null,"NODE_CODE_REPEATED");
   ConfigurationException.require(node.xMm()!=null&&node.yMm()!=null,"NODE_POSITION_REQUIRED");
   String waitFor=upper(node.waitFor());
   ConfigurationException.require(waitFor==null||"AGV".equals(waitFor)||"OHT".equals(waitFor),"WAIT_FOR_INVALID");
   ConfigurationException.require(!(Boolean.TRUE.equals(node.chargePoint())&&waitFor!=null),
    "CHARGE_POINT_NOT_A_WAIT_PLACE");
  }
  for(var segment:document.segments()){
   ConfigurationException.require(nodeIds.containsKey(text(segment.from(),"SEGMENT_FROM")),"SEGMENT_NODE_UNKNOWN");
   ConfigurationException.require(nodeIds.containsKey(text(segment.to(),"SEGMENT_TO")),"SEGMENT_NODE_UNKNOWN");
   ConfigurationException.require(!segment.from().equals(segment.to()),"SEGMENT_ENDS_AT_ITS_START");
  }
  var seen=new HashSet<String>();
  for(var segment:document.segments())
   ConfigurationException.require(seen.add(segment.from()+">"+segment.to()),"SEGMENT_REPEATED");

  // ---- what sits on it ----
  var portCodes=new HashSet<String>();
  var networkOf=new HashMap<String,VehicleType>();   // node code -> the kind of vehicle that reaches it
  // Equipment is the site's, not the map's, so a port names one that is already there. Looked up
  // once: a map with a hundred ports should not ask the database a hundred times for the same set.
  var equipmentIds=new HashMap<String,Long>();
  jdbc.query("SELECT code,id FROM equipment.unit WHERE site_id=?",
   (r,n)->equipmentIds.put(r.getString(1),r.getLong(2)),site);
  for(var port:document.ports()){
   String code=text(port.code(),"PORT_CODE");
   ConfigurationException.require(portCodes.add(code),"PORT_CODE_REPEATED");
   ConfigurationException.require(nodeIds.containsKey(text(port.node(),"PORT_NODE")),"PORT_NODE_UNKNOWN");
   networkOf.put(port.node(),kind(port.accessVehicleType(),"PORT_VEHICLE_TYPE_INVALID"));
   // Naming equipment this site does not have is refused rather than ignored. A port that silently
   // lost its machine is a handover place the controller will never ask about.
   if(port.equipment()!=null&&!port.equipment().isBlank())
    ConfigurationException.require(equipmentIds.containsKey(port.equipment()),"PORT_EQUIPMENT_UNKNOWN");
  }
  var vehicleCodes=new HashSet<String>();
  for(var vehicle:document.vehicles()){
   String code=text(vehicle.code(),"VEHICLE_CODE");
   ConfigurationException.require(vehicleCodes.add(code),"VEHICLE_CODE_REPEATED");
   kind(vehicle.vType(),"VEHICLE_TYPE_INVALID");
   for(String node:List.of(text(vehicle.initialNode(),"VEHICLE_INITIAL_NODE")))
    ConfigurationException.require(nodeIds.containsKey(node),"VEHICLE_NODE_UNKNOWN");
   if(vehicle.home()!=null) ConfigurationException.require(nodeIds.containsKey(vehicle.home()),"VEHICLE_NODE_UNKNOWN");
   if(vehicle.charge()!=null) ConfigurationException.require(nodeIds.containsKey(vehicle.charge()),"VEHICLE_NODE_UNKNOWN");
   String policy=upper(vehicle.chargingPolicy());
   ConfigurationException.require(policy==null||"DISABLED".equals(policy)||vehicle.charge()!=null,
    "CHARGING_NEEDS_A_CHARGE_NODE");
  }
  var zoneCodes=new HashSet<String>();
  var zoned=new HashSet<String>();
  for(var zone:document.zones()){
   ConfigurationException.require(zoneCodes.add(text(zone.code(),"ZONE_CODE")),"ZONE_CODE_REPEATED");
   ConfigurationException.require(zone.capacity()!=null&&zone.capacity()>=1&&zone.capacity()<=100,"ZONE_CAPACITY_INVALID");
   String kind=upper(zone.kind());
   ConfigurationException.require(kind==null||"TRAFFIC".equals(kind)||"MAINTENANCE".equals(kind),"ZONE_KIND_INVALID");
   for(String node:zone.nodes()){
    ConfigurationException.require(nodeIds.containsKey(node),"ZONE_NODE_UNKNOWN");
    // One node counted in two zones would make one vehicle fill two capacities at once.
    ConfigurationException.require(zoned.add(node),"ZONE_NODE_IN_TWO_ZONES");
   }
  }

  // ---- written as a revision of its own, left in draft ----
  long revisionId=guard.nextId();
  int number=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM topology.map_revision WHERE site_id=?",
   Integer.class,site);
  jdbc.update("INSERT INTO topology.map_revision(id,site_id,revision,status) VALUES(?,?,?,'DRAFT')",
   revisionId,site,number);
  for(var node:document.nodes()){
   long id=guard.nextId();
   nodeIds.put(node.code(),id);
   jdbc.update("INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled,stop_node,merge_point,"
    +"charge_point,wait_for) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
    id,site,revisionId,node.code(),node.xMm(),node.yMm(),!Boolean.FALSE.equals(node.enabled()),
    Boolean.TRUE.equals(node.stopNode()),Boolean.TRUE.equals(node.mergePoint()),
    Boolean.TRUE.equals(node.chargePoint()),upper(node.waitFor()));
  }
  for(var segment:document.segments()){
   long from=nodeIds.get(segment.from()), to=nodeIds.get(segment.to());
   jdbc.update("INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)"
    +" VALUES(?,?,?,?,?,?,?,?)",
    guard.nextId(),site,revisionId,from,to,
    segment.costMm()!=null?segment.costMm():straightLine(document,segment),
    !Boolean.FALSE.equals(segment.enabled()),segment.curveMm()!=null?segment.curveMm():0L);
  }
  for(var port:document.ports())
   jdbc.update("INSERT INTO topology.port(id,site_id,revision_id,code,port_type,access_vehicle_type,"
    +"linked_node_id,equipment_unit_id) VALUES(?,?,?,?,?,?,?,?)",
    guard.nextId(),site,revisionId,port.code(),
    port.portType()==null||port.portType().isBlank()?"LOAD":upper(port.portType()),
    upper(port.accessVehicleType()),nodeIds.get(port.node()),
    port.equipment()==null||port.equipment().isBlank()?null:equipmentIds.get(port.equipment()));
  for(var vehicle:document.vehicles())
   jdbc.update("INSERT INTO fleet.vehicle_profile(id,site_id,revision_id,code,name,v_type,initial_node_id,"
    +"home_node_id,charge_node_id,enabled,assignment_enabled,charging_policy,speed_mm_s)"
    +" VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
    guard.nextId(),site,revisionId,vehicle.code(),
    vehicle.name()==null||vehicle.name().isBlank()?vehicle.code():vehicle.name(),
    upper(vehicle.vType()),nodeIds.get(vehicle.initialNode()),
    vehicle.home()==null?null:nodeIds.get(vehicle.home()),
    vehicle.charge()==null?null:nodeIds.get(vehicle.charge()),
    !Boolean.FALSE.equals(vehicle.enabled()),!Boolean.FALSE.equals(vehicle.assignmentEnabled()),
    vehicle.chargingPolicy()==null?"DISABLED":upper(vehicle.chargingPolicy()),
    vehicle.speedMmS()==null?1000:vehicle.speedMmS());
  for(var zone:document.zones()){
   long id=guard.nextId();
   jdbc.update("INSERT INTO topology.zone(id,site_id,revision_id,code,capacity,kind,enabled) VALUES(?,?,?,?,?,?,TRUE)",
    id,site,revisionId,zone.code(),zone.capacity(),zone.kind()==null?"TRAFFIC":upper(zone.kind()));
   for(String node:zone.nodes())
    jdbc.update("INSERT INTO topology.zone_node(zone_id,site_id,revision_id,node_id) VALUES(?,?,?,?)",
     id,site,revisionId,nodeIds.get(node));
  }
  return topology.revisions(site).stream().filter(r->r.id()==revisionId).findFirst().orElseThrow();
 }

 /** Reads a revision back out in the same shape a file comes in, so a map can be taken and edited. */
 @Transactional(readOnly=true)
 public MapDocument export(long site,long revisionId){
  guard.requireSite(site);
  var map=topology.revisions(site).stream().filter(r->r.id()==revisionId).findFirst()
   .orElseThrow(()->new ConfigurationException(404,"REVISION_NOT_FOUND"));
  var nodes=jdbc.query("SELECT code,x_mm,y_mm,enabled,stop_node,merge_point,charge_point,wait_for"
   +" FROM topology.node WHERE site_id=? AND revision_id=? ORDER BY code",
   (r,n)->new MapDocument.Node(r.getString(1),r.getLong(2),r.getLong(3),r.getBoolean(4),
    r.getBoolean(5),r.getBoolean(6),r.getBoolean(7),r.getString(8)),site,map.id());
  var segments=jdbc.query("SELECT f.code,t.code,e.cost,e.enabled,e.curve_mm FROM topology.edge e"
   +" JOIN topology.node f ON f.site_id=e.site_id AND f.revision_id=e.revision_id AND f.id=e.from_node_id"
   +" JOIN topology.node t ON t.site_id=e.site_id AND t.revision_id=e.revision_id AND t.id=e.to_node_id"
   +" WHERE e.site_id=? AND e.revision_id=? ORDER BY f.code,t.code",
   (r,n)->new MapDocument.Segment(r.getString(1),r.getString(2),r.getLong(3),r.getBoolean(4),r.getLong(5)),
   site,map.id());
  var ports=jdbc.query("SELECT p.code,n.code,p.port_type,p.access_vehicle_type,u.code FROM topology.port p"
   +" JOIN topology.node n ON n.site_id=p.site_id AND n.revision_id=p.revision_id AND n.id=p.linked_node_id"
   +" LEFT JOIN equipment.unit u ON u.id=p.equipment_unit_id"
   +" WHERE p.site_id=? AND p.revision_id=? ORDER BY p.code",
   (r,n)->new MapDocument.Port(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),
   site,map.id());
  var vehicles=jdbc.query("SELECT v.code,v.name,v.v_type,i.code,h.code,c.code,v.speed_mm_s,v.charging_policy,"
   +"v.enabled,v.assignment_enabled FROM fleet.vehicle_profile v"
   +" JOIN topology.node i ON i.site_id=v.site_id AND i.revision_id=v.revision_id AND i.id=v.initial_node_id"
   +" LEFT JOIN topology.node h ON h.site_id=v.site_id AND h.revision_id=v.revision_id AND h.id=v.home_node_id"
   +" LEFT JOIN topology.node c ON c.site_id=v.site_id AND c.revision_id=v.revision_id AND c.id=v.charge_node_id"
   +" WHERE v.site_id=? AND v.revision_id=? ORDER BY v.code",
   (r,n)->new MapDocument.Vehicle(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
    r.getString(6),r.getInt(7),r.getString(8),r.getBoolean(9),r.getBoolean(10)),site,map.id());
  var zones=jdbc.query("SELECT z.id,z.code,z.capacity,z.kind FROM topology.zone z"
   +" WHERE z.site_id=? AND z.revision_id=? ORDER BY z.code",
   (r,n)->new MapDocument.Zone(r.getString(2),r.getInt(3),r.getString(4),
    jdbc.queryForList("SELECT n.code FROM topology.zone_node zn JOIN topology.node n ON n.id=zn.node_id"
     +" WHERE zn.zone_id=? ORDER BY n.code",String.class,r.getLong(1))),site,map.id());
  return new MapDocument(nodes,segments,ports,vehicles,zones);
 }

 /** What the segment costs when the file does not say: how far it actually is. */
 private static long straightLine(MapDocument document,MapDocument.Segment segment){
  var from=document.nodes().stream().filter(n->n.code().equals(segment.from())).findFirst().orElseThrow();
  var to=document.nodes().stream().filter(n->n.code().equals(segment.to())).findFirst().orElseThrow();
  double dx=to.xMm()-from.xMm(), dy=to.yMm()-from.yMm();
  return Math.max(1,Math.round(Math.sqrt(dx*dx+dy*dy)));
 }
 private static String text(String value,String code){
  ConfigurationException.require(value!=null&&!value.isBlank(),code+"_REQUIRED");
  ConfigurationInput.text(value,64,code);
  return value;
 }
 private static String upper(String value){
  return value==null||value.isBlank()?null:value.trim().toUpperCase();
 }
 private static VehicleType kind(String value,String code){
  try{ return VehicleType.valueOf(upper(value)); }
  catch(RuntimeException e){ throw new ConfigurationException(422,code); }
 }
}
