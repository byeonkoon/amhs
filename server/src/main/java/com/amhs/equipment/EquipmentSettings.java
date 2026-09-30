package com.amhs.equipment;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;
import java.util.Set;
/** One piece of equipment the controller serves. */
public record EquipmentSettings(long revisionId,String code,String name,String kind,boolean enabled){
 public static final Set<String> KINDS=Set.of("STOCKER","BUFFER","PROCESS");
 public void validate(){
  ConfigurationInput.positive(revisionId,"REVISION");
  ConfigurationInput.text(code,64,"CODE");
  ConfigurationException.require(KINDS.contains(kind==null?"":kind),"INVALID_EQUIPMENT_KIND");
 }
}
