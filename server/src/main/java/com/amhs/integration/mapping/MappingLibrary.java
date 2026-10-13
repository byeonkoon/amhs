package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mapping documents in use, by the name each one calls itself.
 *
 * <p>Two layers, and the order between them is the point. Underneath are the documents shipped with
 * the server, read from a directory: examples, and the dialects that are the same everywhere. Above
 * them is what the site has installed, kept with the rest of its configuration where it can be
 * uploaded, versioned and accounted for. A site's own document of the same name wins, because a
 * site that has written one has a reason.
 *
 * <p>A document that cannot be read does not stop the others and does not stop the server: it is
 * named in the log with everything wrong with it, and whatever asked for it stays unconfigured --
 * the honest state for a machine whose dialect nobody could read.
 */
@Component
public class MappingLibrary {
 private static final Logger log=LoggerFactory.getLogger(MappingLibrary.class);
 private final ObjectMapper json; private final JdbcTemplate jdbc; private final Path directory;
 private final Map<String,DeviceMessageMapping> builtIn=new ConcurrentHashMap<>();
 private final Map<String,List<String>> refused=new ConcurrentHashMap<>();
 /** What each site has installed, read once and kept until that site's configuration changes. */
 private final Map<Long,Map<String,DeviceMessageMapping>> installed=new ConcurrentHashMap<>();

 public MappingLibrary(ObjectMapper json,JdbcTemplate jdbc,
                       @Value("${amhs.device.mappings:config/mappings}") String directory){
  this.json=json; this.jdbc=jdbc; this.directory=Path.of(directory);
  reload();
 }

 /** Reads the shipped directory again. Returns how many documents it now has. */
 public int reload(){
  builtIn.clear(); refused.clear();
  if(!Files.isDirectory(directory)){
   log.info("no mapping directory at {}; only what sites have installed is in use",directory.toAbsolutePath());
   return 0;
  }
  try(var files=Files.list(directory)){
   for(Path file:files.filter(f->f.toString().endsWith(".json")).toList()){
    try{
     var mapping=DeviceMessageMapping.read(json,Files.readString(file));
     var clash=builtIn.put(mapping.code(),mapping);
     if(clash!=null) log.warn("two mapping documents call themselves {}; {} is the one in use",mapping.code(),file);
    }catch(DeviceMessageMapping.MappingRejected e){
     refused.put(file.getFileName().toString(),e.problems());
     log.error("mapping {} refused: {}",file,String.join("; ",e.problems()));
    }catch(IOException e){
     refused.put(file.getFileName().toString(),List.of(e.getMessage()));
     log.error("mapping {} could not be read: {}",file,e.getMessage());
    }
   }
  }catch(IOException e){ log.error("mapping directory {} could not be listed: {}",directory,e.getMessage()); }
  log.info("{} mapping document(s) shipped with the server, {} refused",builtIn.size(),refused.size());
  return builtIn.size();
 }

 /** This site's document of that name, or the one shipped with the server, or neither. */
 public Optional<DeviceMessageMapping> byCode(long siteId,String code){
  if(code==null) return Optional.empty();
  var own=ofSite(siteId).get(code);
  return Optional.ofNullable(own!=null?own:builtIn.get(code));
 }
 public boolean has(long siteId,String code){ return byCode(siteId,code).isPresent(); }

 /** Said when a site's documents have changed, so the next read sees them. */
 public void forget(long siteId){ installed.remove(siteId); }

 /**
  * This site's installed documents, read once and kept.
  *
  * <p>The read happens outside the map rather than inside {@code computeIfAbsent}: it goes to the
  * database, and a lock held across a query is a lock held for as long as the database takes. Two
  * threads arriving together may both read, which costs one query and decides nothing -- they would
  * both have read the same rows.
  */
 private Map<String,DeviceMessageMapping> ofSite(long siteId){
  var known=installed.get(siteId);
  if(known!=null) return known;
  var out=new LinkedHashMap<String,DeviceMessageMapping>();
  jdbc.query("SELECT code,document FROM integration.message_mapping WHERE site_id=? ORDER BY code",(r,n)->{
   try{ return Map.entry(r.getString(1),DeviceMessageMapping.read(json,r.getString(2))); }
   catch(IOException|RuntimeException e){
    // It was judged when it was stored. If it cannot be read now, the reader has changed under
    // it; the site is told rather than silently given the shipped one in its place.
    log.error("installed mapping {} for site {} can no longer be read: {}",r.getString(1),siteId,e.getMessage());
    return null;
   }
  },siteId).forEach(entry->{ if(entry!=null) out.put(entry.getKey(),entry.getValue()); });
  var read=Collections.unmodifiableMap(out);
  installed.put(siteId,read);
  return read;
 }

 /** Which documents are in use for this site, and what each is read by. */
 public Map<String,String> inUse(long siteId){
  var out=new LinkedHashMap<String,String>();
  builtIn.forEach((code,mapping)->out.put(code,mapping.adapter()));
  ofSite(siteId).forEach((code,mapping)->out.put(code,mapping.adapter()));
  return out;
 }
 /** What is wrong with the shipped documents that are not in use. */
 public Map<String,List<String>> refused(){ return Map.copyOf(refused); }
}
