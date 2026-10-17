package com.amhs.integration;
import com.amhs.platform.*;
import java.util.Set;
/**
 * One link, and who is allowed to be at the other end of it.
 *
 * @param tls whether the link is TLS. There is no fallback: a link that asks for it and cannot
 *        have it does not open, because carrying on in plaintext is indistinguishable from above.
 * @param peerName the name the far side's certificate must carry; empty means the address dialled.
 *        For a dial-in profile this is the whole of "who arrived" -- what it may then speak for is
 *        a separate question, answered by the vehicles wired to this profile.
 */
public record ConnectionSettings(String commType,String role,String host,int port,boolean enabled,
                                 boolean tls,String peerName){
 /** The four-column form this was before links could be secured, for callers that mean plaintext. */
 public ConnectionSettings(String commType,String role,String host,int port,boolean enabled){
  this(commType,role,host,port,enabled,false,null);
 }
 public void validate(){
  ConfigurationInput.text(host,255,"HOST");
  ConfigurationException.require(host.matches("[a-zA-Z0-9.:%_-]+"),"INVALID_HOST");
  ConfigurationException.require(port>=1&&port<=65535,"INVALID_PORT");
  if(peerName!=null&&!peerName.isBlank()){
   ConfigurationInput.text(peerName,255,"PEER_NAME");
   ConfigurationException.require(peerName.matches("[a-zA-Z0-9.*_-]+"),"INVALID_PEER_NAME");
   // A name to check with nothing to check it on does nothing, which is worse than nothing at all:
   // it reads as protection.
   ConfigurationException.require(tls,"PEER_NAME_NEEDS_TLS");
  }
  // The kinds of link that exist, which are now exactly the kinds this build can open: TCP either
  // way round -- dialling out to a controller, or holding a port open for a gateway that dials in
  // -- and an MQTT broker. There is no second check that a profile may only be switched on where
  // an adapter exists, because there is no longer a combination this accepts and nothing can open.
  ConfigurationException.require(("TCP".equals(commType)&&Set.of("CLIENT","SERVER").contains(role==null?"":role))
    ||("MQTT".equals(commType)&&"BROKER".equals(role)),"INVALID_CONNECTION_ROLE");
 }
}
