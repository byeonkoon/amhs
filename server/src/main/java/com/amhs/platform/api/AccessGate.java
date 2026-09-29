package com.amhs.platform.api;
import com.amhs.platform.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/**
 * A16 access control and operation audit.
 * Reads stay open for local development. Every state-changing request must present a token,
 * must satisfy the minimum role for its path, and is recorded with actor, target, result and reason.
 * Configuration changes require ADMIN; other operations require OPERATOR.
 */
@Component
class AccessGate extends OncePerRequestFilter {
 private static final Logger log=LoggerFactory.getLogger(AccessGate.class);
 private static final Set<String> READS=Set.of("GET","HEAD","OPTIONS");
 private final boolean localTokenEnabled; private final String localToken;
 private final ObjectMapper mapper; private final AccountQuery accounts; private final AuditRecorder audit;
 AccessGate(@Value("${amhs.configuration.writes-enabled:false}") boolean localTokenEnabled,
  @Value("${amhs.configuration.write-token:}") String localToken,
  ObjectMapper mapper,AccountQuery accounts,AuditRecorder audit){
  this.localTokenEnabled=localTokenEnabled;this.localToken=localToken;this.mapper=mapper;this.accounts=accounts;this.audit=audit;
 }
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  if(READS.contains(req.getMethod())){chain.doFilter(req,res);return;}
  String traceId=UUID.randomUUID().toString();
  String target=req.getMethod()+" "+decode(req.getRequestURI());
  String reason=req.getHeader("X-Amhs-Reason");
  Principal actor=resolve(req.getHeader("Authorization"));
  if(actor==null){deny(res,401,"PRINCIPAL_REQUIRED",traceId,Principal.anonymous(),req.getMethod(),target,reason);return;}
  Role required=requiredRole(req.getRequestURI());
  if(!actor.role().allows(required)){deny(res,403,"ROLE_"+required+"_REQUIRED",traceId,actor,req.getMethod(),target,reason);return;}
  try{
   chain.doFilter(req,res);
   record(actor,req.getMethod(),target,res.getStatus()<400?"ALLOWED":"FAILED",res.getStatus(),reason,traceId);
  }catch(IOException|ServletException|RuntimeException ex){
   record(actor,req.getMethod(),target,"FAILED",500,reason,traceId);
   throw ex;
  }
 }
 /** Configuration endpoints change stored contracts, so they need the highest authority. */
 private static Role requiredRole(String uri){return decode(uri).contains("/configuration/")?Role.ADMIN:Role.OPERATOR;}
 private Principal resolve(String header){
  if(header==null||!header.startsWith("Bearer "))return null;
  String supplied=header.substring(7);
  if(localTokenEnabled&&localToken.length()>=32
   &&MessageDigest.isEqual(localToken.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
   return Principal.localAdmin();
  return accounts.findByTokenHash(sha256Hex(supplied)).orElse(null);
 }
 static String sha256Hex(String value){
  try{
   var digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
   var out=new StringBuilder(64);
   for(byte b:digest)out.append(Character.forDigit((b>>4)&0xF,16)).append(Character.forDigit(b&0xF,16));
   return out.toString();
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
 }
 private static String decode(String uri){
  try{return java.net.URLDecoder.decode(uri,StandardCharsets.UTF_8);}catch(RuntimeException e){return uri;}
 }
 private void deny(HttpServletResponse res,int status,String code,String traceId,Principal actor,
  String action,String target,String reason)throws IOException{
  record(actor,action,target,"DENIED",status,reason,traceId);
  res.setStatus(status);res.setContentType("application/json");
  mapper.writeValue(res.getOutputStream(),Map.of("code",code,"message",code,"traceId",traceId));
 }
 /** Audit must not turn a successful operation into a failure, so recording errors are logged only. */
 private void record(Principal actor,String action,String target,String result,int status,String reason,String traceId){
  try{audit.record(new AuditEntry(actor,action,target,result,status,reason,traceId));}
  catch(RuntimeException ex){log.error("audit write failed traceId={} target={}",traceId,target,ex);}
 }
}
