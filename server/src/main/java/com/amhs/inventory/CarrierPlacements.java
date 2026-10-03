package com.amhs.inventory;
import com.amhs.equipment.EquipmentConfiguration;
import com.amhs.equipment.SlotView;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationLock;
import com.amhs.transport.TransportLedger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * What is stored, and where.
 *
 * <p>Transport says where a carrier is while an order has it. That answer ends with the order, and
 * a carrier sitting in a stocker belongs to no order. This holds the rest, and it holds it as
 * something a person recorded with a reason rather than as something the system worked out: the
 * controller meets equipment at a port and does not see inside it, so what cell a carrier went into
 * is not knowledge it has. Saying "handed over at this port, cell not known" is an answer; guessing
 * a cell would not be.
 *
 * <p>Recording this does not need the site in maintenance. Where things are changes while the site
 * runs, and a record that may only be corrected during a shutdown is a record that is wrong for
 * most of its life. The manager level is the guard, not the site's mode.
 *
 * <p>Nothing here holds a lock of its own. Each rule has a constraint that decides it: one carrier
 * in one place and one carrier per cell are unique indexes, and a carrier an order is carrying is
 * claimed through the transport ledger for the rest of the transaction. Two people recording into
 * the same cell both read it as empty, so a check in this class could not be the thing that stops
 * the second one.
 */
@Service
public class CarrierPlacements {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 private final EquipmentConfiguration equipment; private final TransportLedger transport;

 public CarrierPlacements(JdbcTemplate jdbc,ConfigurationLock guard,
                          EquipmentConfiguration equipment,TransportLedger transport){
  this.jdbc=jdbc; this.guard=guard; this.equipment=equipment; this.transport=transport;
 }

 @Transactional(readOnly=true)
 public List<Placement> placements(long site){
  guard.requireSite(site);
  return jdbc.query(SELECT+" WHERE site_id=? ORDER BY carrier_code",PLACEMENT,site);
 }

 /**
  * A rack's cells, as configured and as filled.
  *
  * <p>The configuration comes from the equipment module, which owns it, and only what is in each
  * cell is added here. Reading the cells out of equipment's own tables would make this module the
  * second owner of a shape it does not define.
  */
 @Transactional(readOnly=true)
 public List<CellView> cells(long site,long unitId,long rackId){
  var configured=equipment.slots(site,unitId,rackId);
  var held=new LinkedHashMap<Long,Placement>();
  for(var p:jdbc.query(SELECT+" WHERE site_id=? AND slot_id IS NOT NULL",PLACEMENT,site))
   held.put(p.slotId(),p);
  var out=new ArrayList<CellView>(configured.size());
  for(SlotView s:configured){
   var p=held.get(s.id());
   out.add(new CellView(s.id(),s.level(),s.col(),s.code(),s.usable(),s.unusableReason(),
    p==null?null:p.carrierCode(),p==null?null:p.placedAt()));
  }
  return out;
 }

 /**
  * Records a carrier in a cell, moving it there if it was somewhere else.
  *
  * <p>Refused while an unfinished order is carrying it: that order decides where it is, and two
  * answers about one carrier is worse than one answer that is merely incomplete. Refused for a cell
  * marked not to be filled -- somebody decided that for a reason, and filling it anyway while
  * leaving the mark in place turns the mark into noise. Refused for a cell that already holds
  * something, by the index rather than by a check, so two people cannot both be told it is free.
  */
 @Transactional
 public Placement placeInCell(long site,String carrierCode,long unitId,long rackId,long slotId,
                              String reason,String actorCode){
  String carrier=cleanCarrier(carrierCode);
  String why=cleanReason(reason);
  guard.requireSite(site);
  // Takes the carrier's claim for the rest of this transaction as well as answering.
  if(transport.carrierInTransport(site,carrier))
   throw new ConfigurationException(409,"CARRIER_IN_TRANSPORT");
  var cell=equipment.slots(site,unitId,rackId).stream().filter(s->s.id()==slotId).findFirst()
   .orElseThrow(()->new ConfigurationException(404,"CELL_NOT_FOUND"));
  if(!cell.usable()) throw new ConfigurationException(409,"CELL_NOT_USABLE");
  if(!rackEnabled(unitId,rackId)) throw new ConfigurationException(409,"RACK_DISABLED");
  return write(site,carrier,slotId,null,why,actorCode,"CELL_OCCUPIED");
 }

 /**
  * Records a carrier as handed over at a port, with the cell not known.
  *
  * <p>This is what the controller can actually witness at a stocker or buffer. It is kept as its
  * own answer rather than being rounded up to a cell or down to nothing: "it went in there and
  * nobody wrote down where" is a different problem from "nobody knows where it is", and only one
  * of them is somebody forgetting to finish a job.
  */
 @Transactional
 public Placement recordAtPort(long site,String carrierCode,long portId,String reason,String actorCode){
  String carrier=cleanCarrier(carrierCode);
  String why=cleanReason(reason);
  guard.requireSite(site);
  if(transport.carrierInTransport(site,carrier))
   throw new ConfigurationException(409,"CARRIER_IN_TRANSPORT");
  if(jdbc.queryForObject("SELECT count(*) FROM topology.port WHERE id=? AND site_id=?",Integer.class,portId,site)==0)
   throw new ConfigurationException(404,"PORT_NOT_FOUND");
  return write(site,carrier,null,portId,why,actorCode,"PORT_PLACEMENT_CONFLICT");
 }

 /**
  * Forgets where a carrier is, because it left.
  *
  * <p>The row goes rather than being marked gone: this table says where things are, and a carrier
  * that is not here has no place in it. What happened is in the operation record, which keeps who
  * did it and the reason they gave.
  */
 @Transactional
 public void remove(long site,String carrierCode,String reason,String actorCode){
  String carrier=cleanCarrier(carrierCode);
  cleanReason(reason);
  guard.requireSite(site);
  if(jdbc.update("DELETE FROM inventory.placement WHERE site_id=? AND carrier_code=?",site,carrier)==0)
   throw new ConfigurationException(404,"PLACEMENT_NOT_FOUND");
 }

 /** The counts a screen puts at the top, each read the same way the lists below it are. */
 @Transactional(readOnly=true)
 public Summary summary(long site){
  guard.requireSite(site);
  var all=placements(site);
  int inCells=(int)all.stream().filter(Placement::inACell).count();
  int carriersInTransport=(int)transport.listActive(site,500).stream()
   .map(o->o.carrierCode()).filter(c->c!=null&&!c.isBlank()).distinct().count();
  int blocked=jdbc.queryForObject(
   "SELECT count(*) FROM equipment.slot s JOIN equipment.rack r ON r.id=s.rack_id"
   +" JOIN equipment.unit u ON u.id=r.unit_id WHERE u.site_id=? AND s.usable=FALSE",Integer.class,site);
  int cells=jdbc.queryForObject(
   "SELECT count(*) FROM equipment.slot s JOIN equipment.rack r ON r.id=s.rack_id"
   +" JOIN equipment.unit u ON u.id=r.unit_id WHERE u.site_id=?",Integer.class,site);
  return new Summary(all.size(),inCells,all.size()-inCells,carriersInTransport,cells,blocked);
 }

 /**
  * Stored carriers and what is known about where. {@code atPortOnly} are the ones handed over at a
  * port with no cell recorded: not lost, not finished either.
  */
 public record Summary(int stored,int inCells,int atPortOnly,int inTransport,int cells,int blockedCells){}

 private Placement write(long site,String carrier,Long slotId,Long portId,String reason,String actorCode,
                         String conflictCode){
  try{
   int moved=jdbc.update("UPDATE inventory.placement SET slot_id=?,port_id=?,evidence='OPERATOR',"
    +"reason=?,actor_code=?,placed_at=now(),version=version+1 WHERE site_id=? AND carrier_code=?",
    slotId,portId,reason,actorCode,site,carrier);
   if(moved==0)
    jdbc.update("INSERT INTO inventory.placement(site_id,carrier_code,slot_id,port_id,evidence,reason,actor_code)"
     +" VALUES(?,?,?,?,'OPERATOR',?,?)",site,carrier,slotId,portId,reason,actorCode);
  }catch(DuplicateKeyException e){
   // The index decided, not this method: somebody else got there inside the same moment.
   throw new ConfigurationException(409,conflictCode);
  }
  return jdbc.query(SELECT+" WHERE site_id=? AND carrier_code=?",PLACEMENT,site,carrier).getFirst();
 }

 private boolean rackEnabled(long unitId,long rackId){
  var rows=jdbc.query("SELECT r.enabled AND u.enabled FROM equipment.rack r JOIN equipment.unit u ON u.id=r.unit_id"
   +" WHERE r.id=? AND r.unit_id=?",(r,n)->r.getBoolean(1),rackId,unitId);
  return !rows.isEmpty()&&rows.getFirst();
 }
 private static String cleanCarrier(String code){
  ConfigurationException.require(code!=null&&!code.isBlank(),"CARRIER_CODE_REQUIRED");
  String trimmed=code.trim();
  ConfigurationException.require(trimmed.length()<=64,"CARRIER_CODE_TOO_LONG");
  return trimmed;
 }
 /** A record of where something is has to say how anybody knows, or it reads as more than a guess. */
 private static String cleanReason(String reason){
  ConfigurationException.require(reason!=null&&!reason.isBlank(),"PLACEMENT_REASON_REQUIRED");
  String trimmed=reason.trim();
  ConfigurationException.require(trimmed.length()<=256,"PLACEMENT_REASON_TOO_LONG");
  return trimmed;
 }

 private static final String SELECT="SELECT id,site_id,carrier_code,slot_id,port_id,evidence,reason,"
  +"actor_code,placed_at,version FROM inventory.placement";
 private static final RowMapper<Placement> PLACEMENT=(r,n)->new Placement(
  r.getLong(1),r.getLong(2),r.getString(3),
  r.getObject(4)==null?null:r.getLong(4),r.getObject(5)==null?null:r.getLong(5),
  r.getString(6),r.getString(7),r.getString(8),
  r.getObject(9,java.time.OffsetDateTime.class),r.getLong(10));
}
