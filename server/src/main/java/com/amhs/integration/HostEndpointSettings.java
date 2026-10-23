package com.amhs.integration;
import com.amhs.platform.ConfigurationException;
import com.amhs.platform.ConfigurationInput;

/**
 * Where this site's transport notices go.
 *
 * <p>{@code authValue} is a secret and behaves like one: it goes in and never comes back out. A
 * read gives {@code null} there, and saving {@code null} keeps whatever was stored -- so editing
 * the URL does not silently wipe the credential the site is authenticating with.
 */
public record HostEndpointSettings(String url,String authHeader,String authValue,
                                   int timeoutMs,int maxAttempts,boolean enabled,
                                   boolean progressNotices,String mappingCode){
 /** The form these settings had before a host could ask for progress or for its own words. */
 public HostEndpointSettings(String url,String authHeader,String authValue,
                             int timeoutMs,int maxAttempts,boolean enabled){
  this(url,authHeader,authValue,timeoutMs,maxAttempts,enabled,false,null);
 }
 public void validate(){
  if(mappingCode!=null&&!mappingCode.isBlank()) ConfigurationInput.text(mappingCode,64,"HOST_MAPPING_CODE");
  ConfigurationInput.text(url,512,"HOST_URL");
  // Only what this can actually open. A scheme nobody dials is a setting that looks configured.
  ConfigurationException.require(url.startsWith("http://")||url.startsWith("https://"),"HOST_URL_NOT_HTTP");
  ConfigurationException.require(timeoutMs>=200&&timeoutMs<=30000,"INVALID_HOST_TIMEOUT");
  ConfigurationException.require(maxAttempts>=1&&maxAttempts<=50,"INVALID_HOST_ATTEMPTS");
  if(authHeader!=null&&!authHeader.isBlank()){
   ConfigurationInput.text(authHeader,128,"HOST_AUTH_HEADER");
   // Asked of the client that will have to send it, rather than checked against a list copied from
   // its documentation. `Host`, `Connection` and the rest are refused by the HTTP client itself, and
   // a name it will not send is a setting that stops every notice at nought attempts if it is let
   // through here. The client's own answer is the only one that stays right as it changes.
   try{ java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://host.invalid/"))
     .header(authHeader,"probe").build(); }
   catch(IllegalArgumentException e){ throw new ConfigurationException(422,"HOST_AUTH_HEADER_NOT_ALLOWED"); }
  }
 }
 /** The same settings with the secret removed, which is the only form that leaves this server. */
 public HostEndpointSettings withoutSecret(){
  return new HostEndpointSettings(url,authHeader,null,timeoutMs,maxAttempts,enabled,progressNotices,mappingCode);
 }
}
