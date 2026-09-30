package com.amhs.topology;
import com.amhs.platform.ConfigurationInput;
/**
 * A node's editable parameters. Which revision it belongs to and which segments reach it are
 * structure, not parameters, and are not changed here.
 */
public record NodeSettings(String code,long xMm,long yMm,boolean enabled){
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
 }
}
