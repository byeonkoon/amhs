package com.amhs.integration;
import com.amhs.platform.*;
public record CommunicationSettings(long vehicleId,long connectionProfileId,long protocolProfileId,String manufacturer,
 String externalVehicleId,String serialNumber,String interfaceName,boolean enabled){
 public void validate(){
  ConfigurationInput.positive(vehicleId,"VEHICLE");ConfigurationInput.positive(connectionProfileId,"CONNECTION_PROFILE");ConfigurationInput.positive(protocolProfileId,"PROTOCOL_PROFILE");
  component(manufacturer,"MANUFACTURER");component(externalVehicleId,"EXTERNAL_VEHICLE");
  if(serialNumber!=null)component(serialNumber,"SERIAL_NUMBER");if(interfaceName!=null)component(interfaceName,"INTERFACE_NAME");
  ConfigurationException.require(!enabled,"ADAPTER_NOT_IMPLEMENTED");
 }
 private void component(String value,String field){
  ConfigurationInput.text(value,64,field);
  ConfigurationException.require(value.chars().noneMatch(c->c=='/'||c=='+'||c=='#'||Character.isISOControl(c)),"INVALID_"+field);
 }
}
