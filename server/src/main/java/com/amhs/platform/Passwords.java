package com.amhs.platform;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * How a password is stored and checked.
 *
 * <p>Never the password itself: a salted PBKDF2 derivation, so two people who chose the same word
 * do not share a stored value and a stolen table cannot be read back. The iteration count is high
 * enough that guessing costs real time, and it travels with the stored value, so raising it later
 * does not invalidate what is already stored.
 *
 * <p>Comparison is constant time. A check that returns faster for a near-miss tells an attacker
 * how close they were.
 */
public final class Passwords {
 private Passwords(){}
 private static final SecureRandom RANDOM=new SecureRandom();
 private static final String ALGORITHM="PBKDF2WithHmacSHA256";
 private static final int ITERATIONS=210_000;
 private static final int SALT_BYTES=16;
 private static final int KEY_BITS=256;

 /** {@code pbkdf2$<iterations>$<salt>$<derived>}, the parts base64url so the whole is one column. */
 public static String hash(String password){
  ConfigurationException.require(password!=null&&password.length()>=4,"PASSWORD_TOO_SHORT");
  var salt=new byte[SALT_BYTES]; RANDOM.nextBytes(salt);
  return encode(ITERATIONS,salt,derive(password,salt,ITERATIONS));
 }

 /** False for a wrong password, a missing one, and a stored value this build cannot read. */
 public static boolean matches(String password,String stored){
  if(password==null||stored==null) return false;
  var parts=stored.split("\\$");
  if(parts.length!=4||!"pbkdf2".equals(parts[0])) return false;
  try{
   int iterations=Integer.parseInt(parts[1]);
   var salt=Base64.getUrlDecoder().decode(parts[2]);
   var expected=Base64.getUrlDecoder().decode(parts[3]);
   return MessageDigest.isEqual(expected,derive(password,salt,iterations));
  }catch(RuntimeException unreadable){
   return false;
  }
 }

 private static byte[] derive(String password,byte[] salt,int iterations){
  try{
   var spec=new PBEKeySpec(password.toCharArray(),salt,iterations,KEY_BITS);
   return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
  }catch(java.security.GeneralSecurityException e){
   throw new IllegalStateException("password hashing unavailable",e);
  }
 }
 private static String encode(int iterations,byte[] salt,byte[] key){
  var b64=Base64.getUrlEncoder().withoutPadding();
  return "pbkdf2$"+iterations+"$"+b64.encodeToString(salt)+"$"+b64.encodeToString(key);
 }
}
