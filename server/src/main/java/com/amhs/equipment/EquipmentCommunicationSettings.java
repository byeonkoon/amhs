package com.amhs.equipment;
import com.amhs.platform.ConfigurationInput;
/** Which profiles reach a piece of equipment, and what the far side calls it. */
public record EquipmentCommunicationSettings(long unitId,long connectionProfileId,long protocolProfileId,
                                             String externalUnitId,boolean enabled){
 public void validate(){
  ConfigurationInput.positive(unitId,"EQUIPMENT_UNIT");
  ConfigurationInput.positive(connectionProfileId,"CONNECTION_PROFILE");
  ConfigurationInput.positive(protocolProfileId,"PROTOCOL_PROFILE");
  ConfigurationInput.text(externalUnitId,64,"EXTERNAL_UNIT_ID");
 }
}
