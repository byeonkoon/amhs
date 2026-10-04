package com.amhs.topology;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationLock;
import com.amhs.platform.ConfigurationRecord;
import com.amhs.platform.SiteWork;
import com.amhs.traffic.TrafficControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The track itself: what each node and segment is worth and whether it may be used.
 *
 * <p>This is the highest-consequence configuration in the system. A mistake here does not affect
 * one machine; it reaches every vehicle that routes through the place, and it does not announce
 * itself until something cannot get where it was told to go. So it is administrator-only, it
 * needs the site in maintenance like every other configuration change, and it refuses to take
 * anything out of service that a vehicle is currently permitted to occupy.
 */
@Service
public class TopologyConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard; private final TrafficControl traffic;
 private final List<SiteWork> openWork;
 public TopologyConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,TrafficControl traffic,List<SiteWork> openWork){
  this.jdbc=jdbc; this.guard=guard; this.traffic=traffic; this.openWork=List.copyOf(openWork);
 }

 /**
  * The site's map revisions, with how much each one holds and which one is in use.
  *
  * <p>Open to anybody, because which map the site is running is not a setting -- it is what every
  * screen is already showing, and a screen that has to guess it shows the wrong site.
  */
 @Transactional(readOnly=true)
 public List<RevisionView> revisions(long site){
  guard.requireSite(site);
  return jdbc.query("SELECT r.id,r.revision,r.status,"
   +"(SELECT count(*) FROM topology.node n WHERE n.site_id=r.site_id AND n.revision_id=r.id),"
   +"(SELECT count(*) FROM topology.edge e WHERE e.site_id=r.site_id AND e.revision_id=r.id),"
   +"(SELECT count(*) FROM topology.port p WHERE p.site_id=r.site_id AND p.revision_id=r.id),"
   +"(SELECT count(*) FROM fleet.vehicle_profile v WHERE v.site_id=r.site_id AND v.revision_id=r.id)"
   +" FROM topology.map_revision r WHERE r.site_id=? ORDER BY r.revision",
   (rs,n)->new RevisionView(rs.getLong(1),rs.getInt(2),rs.getString(3),
    rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7)),site);
 }
 /** One revision: what it is called, what state it is in, and what it contains. */
 public record RevisionView(long id,int revision,String status,int nodes,int segments,int ports,int vehicles){
  public boolean active(){ return "ACTIVE".equals(status); }
 }

 /**
  * Puts a revision into use. The one in use is retired, not deleted: it is what the finished work
  * was carried out on and the history still points at it.
  *
  * <p>Refused while any work is open. Orders, granted roads and reported positions are all written
  * against a revision; changing it under them would leave records naming nodes that no longer
  * exist. Refused too for a revision with no track or no vehicle, which is a site that cannot do
  * anything -- an easy way to stop a plant with one click.
  */
 @Transactional
 public RevisionView activate(long site,long revisionId){
  guard.acquire(site);
  var all=revisions(site);
  var target=all.stream().filter(r->r.id()==revisionId).findFirst()
   .orElseThrow(()->new ConfigurationException(404,"REVISION_NOT_FOUND"));
  if(target.active()) return target;
  ConfigurationException.require(target.nodes()>0,"REVISION_HAS_NO_TRACK");
  ConfigurationException.require(target.vehicles()>0,"REVISION_HAS_NO_VEHICLE");
  for(var work:openWork){
   String why=work.whyBusy(site);
   if(why!=null) throw new ConfigurationException(409,why);
  }
  jdbc.update("UPDATE topology.map_revision SET status='RETIRED' WHERE site_id=? AND status='ACTIVE'",site);
  jdbc.update("UPDATE topology.map_revision SET status='ACTIVE' WHERE site_id=? AND id=?",site,revisionId);
  return revisions(site).stream().filter(r->r.id()==revisionId).findFirst().orElseThrow();
 }

 @Transactional(readOnly=true)
 public List<ConfigurationRecord<NodeSettings>> nodes(long site,long revision){
  guard.requireSite(site);
  return jdbc.query("SELECT id,version,code,x_mm,y_mm,enabled,stop_node,merge_point,charge_point,wait_for FROM topology.node"
   +" WHERE site_id=? AND revision_id=? ORDER BY id",NODE,site,revision);
 }
 @Transactional(readOnly=true)
 public List<ConfigurationRecord<EdgeSettings>> edges(long site,long revision){
  guard.requireSite(site);
  return jdbc.query("SELECT id,version,from_node_id,to_node_id,cost,enabled,curve_mm FROM topology.edge"
   +" WHERE site_id=? AND revision_id=? ORDER BY id",EDGE,site,revision);
 }

 @Transactional
 public ConfigurationRecord<NodeSettings> updateNode(long site,long revision,long id,long expectedVersion,NodeSettings s){
  guard.acquire(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  var current=node(site,revision,id);
  // Taking a node out of service while a vehicle is permitted to stand on it would let the
  // system route somebody else through a place that is occupied.
  if(current.settings().enabled()&&!s.enabled()&&heldNodes(site).contains(id))
   throw new ConfigurationException(409,"NODE_IN_USE");
  int clash=jdbc.queryForObject("SELECT count(*) FROM topology.node"
   +" WHERE site_id=? AND revision_id=? AND code=? AND id<>?",Integer.class,site,revision,s.code(),id);
  if(clash>0) throw new ConfigurationException(409,"NODE_CODE_IN_USE");
  guard.changed(jdbc.update("UPDATE topology.node SET code=?,x_mm=?,y_mm=?,enabled=?,stop_node=?,merge_point=?,charge_point=?,wait_for=?,version=version+1"
   +" WHERE site_id=? AND revision_id=? AND id=? AND version=?",
   s.code(),s.xMm(),s.yMm(),s.enabled(),s.stopNode(),s.mergePoint(),s.chargePoint(),s.waitFor(),site,revision,id,expectedVersion));
  return node(site,revision,id);
 }

 /**
  * Removes a whole revision and everything in it.
  *
  * <p>Maps accumulate -- a file brought in twice is two of them -- and a list nobody can tidy is a
  * list nobody reads. So a revision can go, with two things true: it is not the one in use, and
  * nothing outside the configuration remembers it. An order, a move, a granted road or a reported
  * position naming a revision is a record of something that happened on that map, and the map is
  * the only thing that says what its node numbers meant.
  */
 @Transactional
 public void deleteRevision(long site,long revision){
  guard.acquire(site);
  var target=revisions(site).stream().filter(r->r.id()==revision).findFirst()
   .orElseThrow(()->new ConfigurationException(404,"REVISION_NOT_FOUND"));
  if(target.active()) throw new ConfigurationException(409,"REVISION_IN_USE");
  refuseIfAny("REVISION_HAS_HISTORY","SELECT count(*) FROM transport.order WHERE site_id=? AND revision_id=?",
   site,revision);
  refuseIfAny("REVISION_HAS_HISTORY","SELECT count(*) FROM transport.relocation WHERE site_id=? AND revision_id=?",
   site,revision);
  refuseIfAny("REVISION_HAS_HISTORY","SELECT count(*) FROM traffic.node_reservation WHERE site_id=? AND revision_id=?",
   site,revision);
  refuseIfAny("REVISION_HAS_HISTORY","SELECT count(*) FROM fleet.vehicle_state WHERE site_id=? AND revision_id=?",
   site,revision);
  jdbc.update("DELETE FROM topology.zone_node WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM topology.zone WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM fleet.vehicle_profile WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM topology.port WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM topology.edge WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM topology.node WHERE site_id=? AND revision_id=?",site,revision);
  jdbc.update("DELETE FROM topology.map_revision WHERE site_id=? AND id=?",site,revision);
 }

 /**
  * Adds a node to a revision. A node on its own is unreachable until a segment reaches it, which
  * is the honest state of a layout being drawn and is left to say so rather than be guessed at.
  */
 @Transactional
 public ConfigurationRecord<NodeSettings> createNode(long site,long revision,NodeSettings s){
  guard.acquire(site);
  s.validate();
  requireRevision(site,revision);
  int clash=jdbc.queryForObject("SELECT count(*) FROM topology.node WHERE site_id=? AND revision_id=? AND code=?",
   Integer.class,site,revision,s.code());
  if(clash>0) throw new ConfigurationException(409,"NODE_CODE_IN_USE");
  long id=guard.nextId();
  jdbc.update("INSERT INTO topology.node(id,site_id,revision_id,code,x_mm,y_mm,enabled,stop_node,merge_point,"
   +"charge_point,wait_for) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
   id,site,revision,s.code(),s.xMm(),s.yMm(),s.enabled(),s.stopNode(),s.mergePoint(),s.chargePoint(),s.waitFor());
  return node(site,revision,id);
 }

 /**
  * Removes a node, and only one nothing points at.
  *
  * <p>A node is named by segments, ports, vehicle settings and zones. Letting it go while any of
  * them still name it would turn a layout into one that cannot be read -- so each is reported by
  * name instead, and the person removes the thing that depends on it first. The site also has to
  * be quiet: open work names nodes too, and that naming is not in the configuration at all.
  */
 @Transactional
 public void deleteNode(long site,long revision,long id){
  guard.acquire(site);
  node(site,revision,id);
  requireNothingOpen(site);
  if(heldNodes(site).contains(id)) throw new ConfigurationException(409,"NODE_IN_USE");
  refuseIfAny("NODE_HAS_SEGMENTS","SELECT count(*) FROM topology.edge WHERE site_id=? AND revision_id=?"
   +" AND (from_node_id=? OR to_node_id=?)",site,revision,id,id);
  refuseIfAny("NODE_HAS_PORT","SELECT count(*) FROM topology.port WHERE site_id=? AND revision_id=?"
   +" AND linked_node_id=?",site,revision,id);
  refuseIfAny("NODE_IN_VEHICLE_SETTINGS","SELECT count(*) FROM fleet.vehicle_profile WHERE site_id=? AND revision_id=?"
   +" AND (initial_node_id=? OR home_node_id=? OR charge_node_id=?)",site,revision,id,id,id);
  refuseIfAny("NODE_IN_ZONE","SELECT count(*) FROM topology.zone_node WHERE site_id=? AND revision_id=? AND node_id=?",
   site,revision,id);
  jdbc.update("DELETE FROM topology.node WHERE site_id=? AND revision_id=? AND id=?",site,revision,id);
 }

 /** Adds a one-way segment. Both ends must be in this revision, and the pair must be new. */
 @Transactional
 public ConfigurationRecord<EdgeSettings> createEdge(long site,long revision,EdgeSettings s){
  guard.acquire(site);
  s.validate();
  requireRevision(site,revision);
  ConfigurationException.require(s.fromNodeId()!=s.toNodeId(),"SEGMENT_ENDS_AT_ITS_START");
  node(site,revision,s.fromNodeId());
  node(site,revision,s.toNodeId());
  int clash=jdbc.queryForObject("SELECT count(*) FROM topology.edge WHERE site_id=? AND revision_id=?"
   +" AND from_node_id=? AND to_node_id=?",Integer.class,site,revision,s.fromNodeId(),s.toNodeId());
  if(clash>0) throw new ConfigurationException(409,"SEGMENT_ALREADY_EXISTS");
  long id=guard.nextId();
  jdbc.update("INSERT INTO topology.edge(id,site_id,revision_id,from_node_id,to_node_id,cost,enabled,curve_mm)"
   +" VALUES(?,?,?,?,?,?,?,?)",id,site,revision,s.fromNodeId(),s.toNodeId(),s.cost(),s.enabled(),s.curveMm());
  return edge(site,revision,id);
 }

 /**
  * Removes a segment. Nothing in the configuration names a segment, so what has to be true is that
  * nobody is driving on one: the site quiet, and neither end held.
  */
 @Transactional
 public void deleteEdge(long site,long revision,long id){
  guard.acquire(site);
  var current=edge(site,revision,id).settings();
  requireNothingOpen(site);
  var held=heldNodes(site);
  if(held.contains(current.fromNodeId())||held.contains(current.toNodeId()))
   throw new ConfigurationException(409,"SEGMENT_IN_USE");
  jdbc.update("DELETE FROM topology.edge WHERE site_id=? AND revision_id=? AND id=?",site,revision,id);
 }

 private void requireRevision(long site,long revision){
  Integer count=jdbc.queryForObject("SELECT count(*) FROM topology.map_revision WHERE site_id=? AND id=?",
   Integer.class,site,revision);
  if(count==null||count==0) throw new ConfigurationException(404,"REVISION_NOT_FOUND");
 }
 /** Nothing may be removed from under work that is still going on. */
 private void requireNothingOpen(long site){
  for(var work:openWork){
   String why=work.whyBusy(site);
   if(why!=null) throw new ConfigurationException(409,why);
  }
 }
 private void refuseIfAny(String code,String sql,Object... args){
  Integer count=jdbc.queryForObject(sql,Integer.class,args);
  if(count!=null&&count>0) throw new ConfigurationException(409,code);
 }

 @Transactional
 public ConfigurationRecord<EdgeSettings> updateEdge(long site,long revision,long id,long expectedVersion,EdgeSettings s){
  guard.acquire(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate();
  var current=edge(site,revision,id);
  // Endpoints define the segment. Changing them is a different segment, not a changed one.
  ConfigurationException.require(s.fromNodeId()==current.settings().fromNodeId()
   &&s.toNodeId()==current.settings().toNodeId(),"EDGE_ENDPOINTS_FIXED");
  if(current.settings().enabled()&&!s.enabled()){
   var held=heldNodes(site);
   // A vehicle standing at either end may be about to traverse this segment, and its permission
   // was granted while the segment was open.
   if(held.contains(current.settings().fromNodeId())||held.contains(current.settings().toNodeId()))
    throw new ConfigurationException(409,"SEGMENT_IN_USE");
  }
  guard.changed(jdbc.update("UPDATE topology.edge SET cost=?,enabled=?,curve_mm=?,version=version+1"
   +" WHERE site_id=? AND revision_id=? AND id=? AND version=?",
   s.cost(),s.enabled(),s.curveMm(),site,revision,id,expectedVersion));
  return edge(site,revision,id);
 }

 private Set<Long> heldNodes(long site){
  return traffic.held(site).stream().map(h -> h.nodeId()).collect(Collectors.toSet());
 }
 private ConfigurationRecord<NodeSettings> node(long site,long revision,long id){
  var rows=jdbc.query("SELECT id,version,code,x_mm,y_mm,enabled,stop_node,merge_point,charge_point,wait_for FROM topology.node"
   +" WHERE site_id=? AND revision_id=? AND id=?",NODE,site,revision,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"NODE_NOT_FOUND");
  return rows.getFirst();
 }
 private ConfigurationRecord<EdgeSettings> edge(long site,long revision,long id){
  var rows=jdbc.query("SELECT id,version,from_node_id,to_node_id,cost,enabled,curve_mm FROM topology.edge"
   +" WHERE site_id=? AND revision_id=? AND id=?",EDGE,site,revision,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"SEGMENT_NOT_FOUND");
  return rows.getFirst();
 }
 private static final RowMapper<ConfigurationRecord<NodeSettings>> NODE=(r,n)->new ConfigurationRecord<>(
  r.getLong(1),r.getLong(2),new NodeSettings(r.getString(3),r.getLong(4),r.getLong(5),r.getBoolean(6),
   r.getBoolean(7),r.getBoolean(8),r.getBoolean(9),r.getString(10)));
 private static final RowMapper<ConfigurationRecord<EdgeSettings>> EDGE=(r,n)->new ConfigurationRecord<>(
  r.getLong(1),r.getLong(2),new EdgeSettings(r.getLong(3),r.getLong(4),r.getLong(5),r.getBoolean(6),r.getDouble(7)));
}
