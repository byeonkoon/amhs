package com.amhs.equipment;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;
import java.util.Set;
/**
 * One piece of equipment the controller serves.
 *
 * <p>It names no node, and nothing about it changes when the track around it is redrawn, so it
 * belongs to the site rather than to a map revision. The reference runs the other way: a port on
 * some revision points at a unit.
 */
public record EquipmentSettings(String code,String name,String kind,boolean enabled){
 public static final Set<String> KINDS=Set.of("STOCKER","BUFFER","PROCESS");
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
  ConfigurationException.require(KINDS.contains(kind==null?"":kind),"INVALID_EQUIPMENT_KIND");
 }
}
