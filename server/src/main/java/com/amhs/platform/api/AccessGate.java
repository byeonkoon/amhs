package com.amhs.platform.api;
import com.amhs.platform.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
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
 private final ObjectMapper mapper; private final PrincipalResolver principals; private final AuditRecorder audit;
 AccessGate(ObjectMapper mapper,PrincipalResolver principals,AuditRecorder audit){
  this.mapper=mapper;this.principals=principals;this.audit=audit;
 }
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  if(READS.contains(req.getMethod())){chain.doFilter(req,res);return;}
  String traceId=UUID.randomUUID().toString();
  String target=req.getMethod()+" "+decode(req.getRequestURI());
  String reason=com.amhs.platform.RequestReason.fromHeader(req.getHeader("X-Amhs-Reason"));
  Principal actor=principals.resolve(req.getHeader("Authorization")).orElse(null);
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
 /**
  * What a request needs, decided by what it can break rather than by whether it is a write.
  *
  * <p>Running configured work is the operator's job. Configuring the vehicles, ports and
  * equipment that work uses is the engineer's. The track itself, the site-wide options and the
  * accounts are the administrator's, because a mistake there reaches every vehicle at once and
  * is not visible until something drives into it.
  */
 private static Role requiredRole(String uri){
  String path=decode(uri);
  if(!path.contains("/configuration/"))return Role.OPERATOR;
  for(String area:SITE_WIDE)if(path.contains("/configuration/"+area))return Role.ADMIN;
  return Role.ENGINEER;
 }
 private static final List<String> SITE_WIDE=List.of("system","topology","accounts");
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
