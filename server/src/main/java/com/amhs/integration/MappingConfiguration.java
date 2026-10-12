package com.amhs.integration;
import com.amhs.integration.mapping.MappingLibrary;
import com.amhs.integration.mapping.VehicleMessageMapping;
import com.amhs.platform.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The mapping documents a site has installed.
 *
 * <p>A dialect is configuration like any other here, and it was the one piece that was not: a file
 * on the server's disk that no screen could change, no version could be compared and no record said
 * who put there. It is kept with the rest now -- uploaded, versioned, audited -- and the documents
 * shipped with the server stay as the built-in set beneath it.
 *
 * <p>A document is read and judged before it is stored. A site that has saved a mapping has saved
 * one that works; the alternative is finding out when a vehicle will not move.
 */
@Service
public class MappingConfiguration {
 private final JdbcTemplate jdbc; private final ConfigurationLock guard; private final ObjectMapper json;
 private final MappingLibrary library;

 public MappingConfiguration(JdbcTemplate jdbc,ConfigurationLock guard,ObjectMapper json,
                             MappingLibrary library){
  this.jdbc=jdbc; this.guard=guard; this.json=json; this.library=library;
 }

 /** One installed document: what it is for, and what it says, with the document itself. */
 public record Installed(long id,String code,String adapter,String notes,int commands,int inboundRules,
                         String updatedBy,OffsetDateTime updatedAt,long version,String document){}

 private RowMapper<Installed> mapper(){ return (r,n)->{
  var document=r.getString("document");
  int commands=0,rules=0;
  try{
   var read=VehicleMessageMapping.read(json,document);
   commands=read.outbound().size(); rules=read.inbound().size();
  }catch(IOException|RuntimeException ignored){ /* stored ones were valid when stored; say nothing here */ }
  return new Installed(r.getLong("id"),r.getString("code"),r.getString("adapter"),r.getString("notes"),
   commands,rules,r.getString("updated_by"),r.getObject("updated_at",OffsetDateTime.class),
   r.getLong("version"),document);
 }; }

 @Transactional(readOnly=true)
 public List<Installed> list(long site){
  guard.requireSite(site);
  return jdbc.query("SELECT * FROM integration.message_mapping WHERE site_id=? ORDER BY code",mapper(),site);
 }
 @Transactional(readOnly=true)
 public Installed get(long site,String code){
  var rows=jdbc.query("SELECT * FROM integration.message_mapping WHERE site_id=? AND code=?",mapper(),site,code);
  if(rows.isEmpty()) throw new ConfigurationException(404,"MAPPING_NOT_FOUND");
  return rows.getFirst();
 }

 /**
  * Puts a document in use, or replaces the one of that name.
  *
  * <p>The name is the document's own: a protocol profile points at a dialect by what the dialect
  * calls itself, so renaming it in the file is installing a different one, which is what somebody
  * doing that means.
  */
 @Transactional
 /** The actor is recorded against the row; the request itself is audited by the access gate. */
 public Installed save(long site,String document,String actor){
  guard.acquire(site);
  VehicleMessageMapping read;
  try{ read=VehicleMessageMapping.read(json,document); }
  catch(IOException e){ throw new ConfigurationException(422,"MAPPING_NOT_JSON"); }
  catch(VehicleMessageMapping.MappingRejected e){ throw new ConfigurationException(422,"MAPPING_REJECTED: "+e.getMessage()); }
  var existing=jdbc.query("SELECT id,version FROM integration.message_mapping WHERE site_id=? AND code=?",
   (r,n)->new long[]{r.getLong(1),r.getLong(2)},site,read.code()).stream().findFirst().orElse(null);
  if(existing==null){
   long id=guard.nextId();
   jdbc.update("INSERT INTO integration.message_mapping(id,site_id,code,adapter,notes,document,updated_by)"
    +" VALUES(?,?,?,?,?,?,?)",id,site,read.code(),read.adapter(),read.notes(),document,actor);
  }else
   jdbc.update("UPDATE integration.message_mapping SET adapter=?,notes=?,document=?,updated_by=?,"
    +"updated_at=clock_timestamp(),version=version+1 WHERE site_id=? AND code=?",
    read.adapter(),read.notes(),document,actor,site,read.code());
  forget(site);
  return get(site,read.code());
 }

 /**
  * Takes a document out of use.
  *
  * <p>Refused while a vehicle's communication points at a profile that names it, in use or not: a
  * vehicle configured against a dialect nobody can read is a vehicle that looks configured and is
  * not, and the row that says so is the thing to change first. A profile naming it with no vehicle
  * behind it is not in anybody's way, and does not stand in the way of this either.
  */
 @Transactional
 public void remove(long site,String code,String actor){
  guard.acquire(site);
  get(site,code);
  Integer used=jdbc.queryForObject("SELECT count(*) FROM integration.vehicle_communication v"
   +" JOIN integration.protocol_profile p ON p.site_id=v.site_id AND p.id=v.protocol_profile_id"
   +" WHERE v.site_id=? AND p.adapter_key=?",Integer.class,site,code);
  if(used!=null&&used>0) throw new ConfigurationException(409,"MAPPING_IN_USE");
  jdbc.update("DELETE FROM integration.message_mapping WHERE site_id=? AND code=?",site,code);
  forget(site);
 }

 /**
  * Tells the library its copy is out of date -- twice, and both are needed.
  *
  * <p>Now, because whoever is inside this transaction must see what it just wrote. And again after
  * it commits, because another thread that reads in between sees the rows as they were and would
  * cache that answer for good: the write is not visible to it yet, so forgetting now cannot help.
  */
 private void forget(long site){
  library.forget(site);
  if(!TransactionSynchronizationManager.isSynchronizationActive()) return;
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){ library.forget(site); }
  });
 }
}
