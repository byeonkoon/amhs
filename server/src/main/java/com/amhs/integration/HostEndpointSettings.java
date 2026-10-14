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
                                   int timeoutMs,int maxAttempts,boolean enabled){
 public void validate(){
  ConfigurationInput.text(url,512,"HOST_URL");
  // Only what this can actually open. A scheme nobody dials is a setting that looks configured.
  ConfigurationException.require(url.startsWith("http://")||url.startsWith("https://"),"HOST_URL_NOT_HTTP");
  ConfigurationException.require(timeoutMs>=200&&timeoutMs<=30000,"INVALID_HOST_TIMEOUT");
  ConfigurationException.require(maxAttempts>=1&&maxAttempts<=50,"INVALID_HOST_ATTEMPTS");
  if(authHeader!=null&&!authHeader.isBlank())
   ConfigurationInput.text(authHeader,128,"HOST_AUTH_HEADER");
 }
 /** The same settings with the secret removed, which is the only form that leaves this server. */
 public HostEndpointSettings withoutSecret(){
  return new HostEndpointSettings(url,authHeader,null,timeoutMs,maxAttempts,enabled);
 }
}
