package com.amhs.platform;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Reads the free-text reason a client attaches to a change.
 *
 * <p>HTTP header values are limited to ISO-8859-1, and a browser refuses to send anything else, so
 * a reason written in Korean cannot travel as it is. Clients percent-encode it as UTF-8; this turns
 * it back. A value that is not valid percent-encoding is kept as sent rather than lost.
 */
public final class RequestReason {
 private RequestReason(){}

 public static String fromHeader(String header){
  if(header==null||header.isBlank()) return header;
  try{
   // '+' stays a plus: the client encodes spaces as %20, never as '+'.
   return URLDecoder.decode(header.replace("+","%2B"),StandardCharsets.UTF_8);
  }catch(IllegalArgumentException malformed){
   return header;
  }
 }
}
