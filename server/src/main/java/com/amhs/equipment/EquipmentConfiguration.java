package com.amhs.equipment;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationLock;
import com.amhs.platform.ConfigurationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * Equipment, its racks and their cells.
 *
 * <p>Cells are generated from the rack's size rather than entered, so the two cannot disagree.
 * Growing a rack adds the new cells. Shrinking removes cells, and is refused when any cell it
 * would remove has been marked unusable: that mark is something a person recorded about a real
 * place, and silently dropping it would lose the reason a cell was being kept empty.
 */
@Service
public class EquipmentConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard;
 public EquipmentConfiguration(JdbcTemplate jdbc,ConfigurationLock guard){this.jdbc=jdbc;this.guard=guard;}

 @Transactional(readOnly=true)
 public List<ConfigurationRecord<EquipmentSettings>> units(long site){
  guard.requireSite(site);
  return jdbc.query("SELECT id,version,revision_id,code,name,kind,enabled FROM equipment.unit"
   +" WHERE site_id=? ORDER BY id",UNIT,site);
 }

 @Transactional
 public ConfigurationRecord<EquipmentSettings> createUnit(long site,EquipmentSettings s){
  guard.acquire(site); s.validate(); requireRevision(site,s.revisionId());
  long id=guard.nextId();
  jdbc.update("INSERT INTO equipment.unit(id,site_id,revision_id,code,name,kind,enabled) VALUES(?,?,?,?,?,?,?)",
   id,site,s.revisionId(),s.code(),s.name()==null?"":s.name(),s.kind(),s.enabled());
  return unit(site,id);
 }

 @Transactional
 public ConfigurationRecord<EquipmentSettings> updateUnit(long site,long id,long expectedVersion,EquipmentSettings s){
  guard.acquire(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate(); requireRevision(site,s.revisionId()); unit(site,id);
  guard.changed(jdbc.update("UPDATE equipment.unit SET revision_id=?,code=?,name=?,kind=?,enabled=?,version=version+1"
   +" WHERE site_id=? AND id=? AND version=?",
   s.revisionId(),s.code(),s.name()==null?"":s.name(),s.kind(),s.enabled(),site,id,expectedVersion));
  return unit(site,id);
 }

 @Transactional(readOnly=true)
 public List<ConfigurationRecord<RackSettings>> racks(long site,long unitId){
  unit(site,unitId);
  return jdbc.query("SELECT id,version,code,levels,columns,enabled FROM equipment.rack"
   +" WHERE unit_id=? ORDER BY id",RACK,unitId);
 }

 @Transactional
 public ConfigurationRecord<RackSettings> createRack(long site,long unitId,RackSettings s){
  guard.acquire(site); s.validate(); unit(site,unitId);
  long id=guard.nextId();
  jdbc.update("INSERT INTO equipment.rack(id,unit_id,code,levels,columns,enabled) VALUES(?,?,?,?,?,?)",
   id,unitId,s.code(),s.levels(),s.columns(),s.enabled());
  generate(id,s.code(),1,s.levels(),1,s.columns());
  return rack(unitId,id);
 }

 @Transactional
 public ConfigurationRecord<RackSettings> updateRack(long site,long unitId,long id,long expectedVersion,RackSettings s){
  guard.acquire(site); ConfigurationException.require(expectedVersion>=0,"INVALID_VERSION");
  s.validate(); unit(site,unitId);
  var current=rack(unitId,id).settings();
  if(s.levels()<current.levels()||s.columns()<current.columns()){
   int marked=jdbc.queryForObject("SELECT count(*) FROM equipment.slot"
    +" WHERE rack_id=? AND usable=FALSE AND (level>? OR col>?)",Integer.class,id,s.levels(),s.columns());
   if(marked>0) throw new ConfigurationException(409,"UNUSABLE_SLOT_WOULD_BE_REMOVED");
   jdbc.update("DELETE FROM equipment.slot WHERE rack_id=? AND (level>? OR col>?)",id,s.levels(),s.columns());
  }
  // Growing only fills in what is missing, so cells that already carry a decision keep it.
  if(s.levels()>current.levels()) generate(id,s.code(),current.levels()+1,s.levels(),1,s.columns());
  if(s.columns()>current.columns()) generate(id,s.code(),1,Math.min(current.levels(),s.levels()),current.columns()+1,s.columns());
  guard.changed(jdbc.update("UPDATE equipment.rack SET code=?,levels=?,columns=?,enabled=?,version=version+1"
   +" WHERE unit_id=? AND id=? AND version=?",s.code(),s.levels(),s.columns(),s.enabled(),unitId,id,expectedVersion));
  if(!s.code().equals(current.code()))
   jdbc.update("UPDATE equipment.slot SET code=?||'-'||lpad(level::text,2,'0')||lpad(col::text,3,'0') WHERE rack_id=?",
    s.code(),id);
  return rack(unitId,id);
 }

 @Transactional(readOnly=true)
 public List<SlotView> slots(long site,long unitId,long rackId){
  unit(site,unitId); rack(unitId,rackId);
  return jdbc.query("SELECT id,rack_id,level,col,code,usable,unusable_reason FROM equipment.slot"
   +" WHERE rack_id=? ORDER BY level,col",SLOT,rackId);
 }

 /** Marks a cell as not to be filled, or clears that mark. This is not occupancy. */
 @Transactional
 public SlotView setSlotUsable(long site,long unitId,long rackId,long slotId,boolean usable,String reason){
  guard.acquire(site); unit(site,unitId); rack(unitId,rackId);
  ConfigurationException.require(usable||reason!=null&&!reason.isBlank(),"UNUSABLE_REASON_REQUIRED");
  int changed=jdbc.update("UPDATE equipment.slot SET usable=?,unusable_reason=? WHERE id=? AND rack_id=?",
   usable,usable?null:reason.substring(0,Math.min(reason.length(),128)),slotId,rackId);
  if(changed==0) throw new ConfigurationException(404,"SLOT_NOT_FOUND");
  return jdbc.query("SELECT id,rack_id,level,col,code,usable,unusable_reason FROM equipment.slot WHERE id=?",SLOT,slotId)
   .getFirst();
 }

 /**
  * A cell's code: the rack, then the column and the level, as {@code R-A 20/08}.
  *
  * <p>The separator is the point. Run together, the two numbers read as one and somebody has to
  * know the convention to see where the column stops -- which is the moment a person standing at a
  * rack gets it wrong. Padded to two digits so a rack's codes line up, and not truncated beyond
  * that: a rack may be 200 columns wide and the hundredth column is still the hundredth column.
  */
 static String slotCode(String rackCode,int level,int col){
  return "%s %02d/%02d".formatted(rackCode,col,level);
 }
 private void generate(long rackId,String code,int fromLevel,int toLevel,int fromCol,int toCol){
  for(int level=fromLevel;level<=toLevel;level++)
   for(int col=fromCol;col<=toCol;col++)
    jdbc.update("INSERT INTO equipment.slot(rack_id,level,col,code) VALUES(?,?,?,?)"
     +" ON CONFLICT(rack_id,level,col) DO NOTHING",
     rackId,level,col,slotCode(code,level,col));
 }
 private void requireRevision(long site,long revisionId){
  int known=jdbc.queryForObject("SELECT count(*) FROM topology.map_revision WHERE site_id=? AND id=?",
   Integer.class,site,revisionId);
  if(known==0) throw new ConfigurationException(422,"REVISION_NOT_FOUND");
 }
 private ConfigurationRecord<EquipmentSettings> unit(long site,long id){
  var rows=jdbc.query("SELECT id,version,revision_id,code,name,kind,enabled FROM equipment.unit"
   +" WHERE site_id=? AND id=?",UNIT,site,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"EQUIPMENT_NOT_FOUND");
  return rows.getFirst();
 }
 private ConfigurationRecord<RackSettings> rack(long unitId,long id){
  var rows=jdbc.query("SELECT id,version,code,levels,columns,enabled FROM equipment.rack"
   +" WHERE unit_id=? AND id=?",RACK,unitId,id);
  if(rows.isEmpty())throw new ConfigurationException(404,"RACK_NOT_FOUND");
  return rows.getFirst();
 }
 private static final RowMapper<ConfigurationRecord<EquipmentSettings>> UNIT=(r,n)->new ConfigurationRecord<>(
  r.getLong(1),r.getLong(2),new EquipmentSettings(r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7)));
 private static final RowMapper<ConfigurationRecord<RackSettings>> RACK=(r,n)->new ConfigurationRecord<>(
  r.getLong(1),r.getLong(2),new RackSettings(r.getString(3),r.getInt(4),r.getInt(5),r.getBoolean(6)));
 private static final RowMapper<SlotView> SLOT=(r,n)->new SlotView(
  r.getLong(1),r.getLong(2),r.getInt(3),r.getInt(4),r.getString(5),r.getBoolean(6),r.getString(7));
}
