package com.amhs.integration.mapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mapping documents this server has, read from a directory and kept by their own name.
 *
 * <p>A directory rather than a table, for now, because a mapping is written and corrected the way a
 * map file is: by hand, in an editor, against a vehicle that is sitting there not working. Where it
 * will live in the end is beside the map revisions -- uploaded, versioned and audited -- and the
 * reading here is what that will replace.
 *
 * <p>A document that cannot be read does not stop the others, and does not stop the server: it is
 * named in the log with everything wrong with it, and the vehicles that asked for it stay
 * unconfigured, which is the honest state for a vehicle whose dialect nobody could read.
 */
@Component
public class MappingLibrary {
 private static final Logger log=LoggerFactory.getLogger(MappingLibrary.class);
 private final ObjectMapper json; private final Path directory;
 private final Map<String,VehicleMessageMapping> byCode=new ConcurrentHashMap<>();
 private final Map<String,List<String>> refused=new ConcurrentHashMap<>();

 public MappingLibrary(ObjectMapper json,@Value("${amhs.device.mappings:config/mappings}") String directory){
  this.json=json; this.directory=Path.of(directory);
  reload();
 }

 /** Reads the directory again. Returns how many documents it now has. */
 public int reload(){
  byCode.clear(); refused.clear();
  if(!Files.isDirectory(directory)){
   log.info("no mapping directory at {}; vehicles that need one stay unconfigured",directory.toAbsolutePath());
   return 0;
  }
  try(var files=Files.list(directory)){
   for(Path file:files.filter(f->f.toString().endsWith(".json")).toList()){
    try{
     var mapping=VehicleMessageMapping.read(json,Files.readString(file));
     var clash=byCode.put(mapping.code(),mapping);
     if(clash!=null) log.warn("two mapping documents call themselves {}; {} is the one in use",mapping.code(),file);
    }catch(VehicleMessageMapping.MappingRejected e){
     refused.put(file.getFileName().toString(),e.problems());
     log.error("mapping {} refused: {}",file,String.join("; ",e.problems()));
    }catch(IOException e){
     refused.put(file.getFileName().toString(),List.of(e.getMessage()));
     log.error("mapping {} could not be read: {}",file,e.getMessage());
    }
   }
  }catch(IOException e){ log.error("mapping directory {} could not be listed: {}",directory,e.getMessage()); }
  log.info("{} mapping document(s) in use, {} refused",byCode.size(),refused.size());
  return byCode.size();
 }

 public Optional<VehicleMessageMapping> byCode(String code){
  return code==null?Optional.empty():Optional.ofNullable(byCode.get(code));
 }
 public boolean has(String code){ return code!=null&&byCode.containsKey(code); }
 /** Which documents are in use, and what is wrong with the ones that are not. */
 public Map<String,String> inUse(){
  var out=new LinkedHashMap<String,String>();
  byCode.forEach((code,mapping)->out.put(code,mapping.adapter()));
  return out;
 }
 public Map<String,List<String>> refused(){ return Map.copyOf(refused); }
}
