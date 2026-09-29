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
  ConfigurationException.require(!enabled,"ADAPTER_NOT_IMPLEMENTED");
 }
}
