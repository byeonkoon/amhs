package com.amhs.equipment;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;
/**
 * A rack as a grid. The cells are generated from these numbers rather than entered separately,
 * so a rack cannot claim a size its cells disagree with.
 */
public record RackSettings(String code,int levels,int columns,boolean enabled){
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
  ConfigurationException.require(levels>=1&&levels<=60,"INVALID_LEVELS");
  ConfigurationException.require(columns>=1&&columns<=200,"INVALID_COLUMNS");
 }
}
