package com.amhs.platform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Turns a presented token into who is asking.
 *
 * <p>Kept apart from the gate that enforces authority so that a screen can ask what it is allowed
 * to do without first attempting something and being refused. Only the hash of a token is ever
 * compared against storage; the local development token is compared in constant time and is
 * accepted only while local writes are explicitly enabled.
 */
@Service
public class PrincipalResolver {
 private final boolean localTokenEnabled; private final String localToken; private final AccountQuery accounts;
 public PrincipalResolver(@Value("${amhs.configuration.writes-enabled:false}") boolean localTokenEnabled,
                          @Value("${amhs.configuration.write-token:}") String localToken,
                          AccountQuery accounts){
  this.localTokenEnabled=localTokenEnabled; this.localToken=localToken; this.accounts=accounts;
 }

 /** Empty when the header is missing, malformed, or names nothing that is enabled. */
 public Optional<Principal> resolve(String authorizationHeader){
  if(authorizationHeader==null||!authorizationHeader.startsWith("Bearer "))return Optional.empty();
  String supplied=authorizationHeader.substring(7);
  if(localTokenEnabled&&localToken.length()>=32
   &&MessageDigest.isEqual(localToken.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
   return Optional.of(Principal.localAdmin());
  return accounts.findByTokenHash(sha256Hex(supplied));
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
