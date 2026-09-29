package com.amhs.topology;
import com.amhs.platform.*;
public record PortSettings(long revisionId,String code,String portType,String makerType,VehicleType accessVehicleType,long linkedNodeId){
 public void validate(){
  ConfigurationInput.positive(revisionId,"REVISION");ConfigurationInput.positive(linkedNodeId,"LINKED_NODE");
  ConfigurationInput.text(code,64,"CODE");ConfigurationInput.text(portType,64,"PORT_TYPE");ConfigurationInput.text(makerType,64,"MAKER_TYPE");
  ConfigurationException.require(accessVehicleType!=null,"ACCESS_VEHICLE_TYPE_REQUIRED");
 }
}
