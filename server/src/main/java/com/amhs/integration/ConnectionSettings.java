package com.amhs.integration;
import com.amhs.platform.*;
import java.util.Set;
public record ConnectionSettings(String commType,String role,String host,int port,boolean enabled){
 public void validate(){
  ConfigurationInput.text(host,255,"HOST");
  ConfigurationException.require(host.matches("[a-zA-Z0-9.:%_-]+"),"INVALID_HOST");
  ConfigurationException.require(port>=1&&port<=65535,"INVALID_PORT");
  // The kinds of link that exist, which are now exactly the kinds this build can open: TCP either
  // way round -- dialling out to a controller, or holding a port open for a gateway that dials in
  // -- and an MQTT broker. There is no second check that a profile may only be switched on where
  // an adapter exists, because there is no longer a combination this accepts and nothing can open.
  ConfigurationException.require(("TCP".equals(commType)&&Set.of("CLIENT","SERVER").contains(role==null?"":role))
    ||("MQTT".equals(commType)&&"BROKER".equals(role)),"INVALID_CONNECTION_ROLE");
 }
}
