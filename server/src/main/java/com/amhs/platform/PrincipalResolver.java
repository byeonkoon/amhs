package com.amhs.platform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Turns what a request presents into who is asking.
 *
 * <p>Normally that is a session from a login. The local development token stays as a way to reach
 * a site that has no usable account yet — a first run, or a forgotten administrator password — and
 * it is off unless local writes are explicitly enabled, so it cannot be the way in by accident.
 *
 * <p>Kept apart from the gate that enforces authority so a screen can ask what it is allowed to do
 * without first attempting something and being refused.
 */
@Service
public class PrincipalResolver {
 private final boolean localTokenEnabled; private final String localToken; private final Logins logins;
 public PrincipalResolver(@Value("${amhs.configuration.writes-enabled:false}") boolean localTokenEnabled,
                          @Value("${amhs.configuration.write-token:}") String localToken,
                          Logins logins){
  this.localTokenEnabled=localTokenEnabled; this.localToken=localToken; this.logins=logins;
 }

 /** Empty when the header is missing, malformed, or names no live session. */
 public Optional<Principal> resolve(String authorizationHeader){
  if(authorizationHeader==null||!authorizationHeader.startsWith("Bearer ")) return Optional.empty();
  String supplied=authorizationHeader.substring(7);
  if(localTokenEnabled&&localToken.length()>=32
   &&MessageDigest.isEqual(localToken.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
   return Optional.of(Principal.localAdmin());
  return logins.resolve(sha256Hex(supplied));
 }

 public static String sha256Hex(String value){
  try{
   var digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
   var out=new StringBuilder(64);
   for(byte b:digest)out.append(Character.forDigit((b>>4)&0xF,16)).append(Character.forDigit(b&0xF,16));
   return out.toString();
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
 }
}
