package com.amhs.integration;
import com.amhs.platform.*;
import java.util.Set;
public record ProtocolSettings(String protoType,String protoVersion,String adapterKey){
 public void validate(){
  ConfigurationException.require(Set.of("PC","PLC","VDA5050").contains(protoType==null?"":protoType),"INVALID_PROTOCOL");
  ConfigurationInput.text(protoVersion,32,"PROTOCOL_VERSION");ConfigurationInput.text(adapterKey,64,"ADAPTER_KEY");
  if("VDA5050".equals(protoType))ConfigurationException.require(Set.of("2.0.0","2.1.0","3.0.0").contains(protoVersion),"UNSUPPORTED_VDA_VERSION");
 }
}
