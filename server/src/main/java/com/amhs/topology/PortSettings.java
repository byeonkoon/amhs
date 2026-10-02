package com.amhs.topology;
import com.amhs.platform.*;
/** {@code equipmentUnitId}: the equipment this port hands over to, or null when none is linked. */
public record PortSettings(long revisionId,String code,String portType,VehicleType accessVehicleType,long linkedNodeId,
                           Long equipmentUnitId){
 public PortSettings(long revisionId,String code,String portType,VehicleType accessVehicleType,long linkedNodeId){
  this(revisionId,code,portType,accessVehicleType,linkedNodeId,null);
 }
 public void validate(){
  ConfigurationInput.positive(revisionId,"REVISION");ConfigurationInput.positive(linkedNodeId,"LINKED_NODE");
  ConfigurationInput.text(code,64,"CODE");ConfigurationInput.text(portType,64,"PORT_TYPE");
  ConfigurationException.require(accessVehicleType!=null,"ACCESS_VEHICLE_TYPE_REQUIRED");
 }
}
