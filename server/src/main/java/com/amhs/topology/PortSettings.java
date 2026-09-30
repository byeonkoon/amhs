package com.amhs.topology;
import com.amhs.platform.*;
public record PortSettings(long revisionId,String code,String portType,VehicleType accessVehicleType,long linkedNodeId){
 public void validate(){
  ConfigurationInput.positive(revisionId,"REVISION");ConfigurationInput.positive(linkedNodeId,"LINKED_NODE");
  ConfigurationInput.text(code,64,"CODE");ConfigurationInput.text(portType,64,"PORT_TYPE");
  ConfigurationException.require(accessVehicleType!=null,"ACCESS_VEHICLE_TYPE_REQUIRED");
 }
}
