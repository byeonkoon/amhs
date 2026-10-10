package com.amhs.integration;
import com.amhs.platform.*;
import java.util.Set;
public record ConnectionSettings(String commType,String role,String host,int port,boolean enabled){
 public void validate(){
  ConfigurationInput.text(host,255,"HOST");
  ConfigurationException.require(host.matches("[a-zA-Z0-9.:%_-]+"),"INVALID_HOST");
  ConfigurationException.require(port>=1&&port<=65535,"INVALID_PORT");
  ConfigurationException.require(("TCP".equals(commType)&&Set.of("CLIENT","SERVER").contains(role==null?"":role))
    ||("MQTT".equals(commType)&&"BROKER".equals(role)),"INVALID_CONNECTION_ROLE");
  // Only the kind of link this build can actually open may be switched on: dialling out over TCP,
  // and an MQTT broker. Listening for a vehicle that dials in is not implemented, and a profile
  // that claims to be in use when nothing can open it is a site that looks connected.
  ConfigurationException.require(!enabled||("TCP".equals(commType)&&"CLIENT".equals(role))||("MQTT".equals(commType)&&"BROKER".equals(role)),"ADAPTER_NOT_IMPLEMENTED");
 }
}
