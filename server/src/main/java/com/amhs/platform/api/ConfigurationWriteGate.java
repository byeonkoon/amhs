package com.amhs.platform.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** Temporary local-development gate. Replace with A16 principal/role/audit enforcement. */
@Component
class ConfigurationWriteGate extends OncePerRequestFilter {
 private final boolean enabled; private final String token; private final ObjectMapper mapper;
 ConfigurationWriteGate(@Value("${amhs.configuration.writes-enabled:false}") boolean enabled,
  @Value("${amhs.configuration.write-token:}") String token,ObjectMapper mapper){this.enabled=enabled;this.token=token;this.mapper=mapper;}
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  // Fail closed for every mutation, including URL-encoded paths. A16 will replace this gate.
  if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())){
   String header=req.getHeader("Authorization");
   if(!enabled||token.length()<32){reject(res,403,"CONFIGURATION_WRITES_DISABLED");return;}
   if(header==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8))){
    reject(res,401,"CONFIGURATION_TOKEN_REQUIRED");return;
   }
  }
  chain.doFilter(req,res);
 }
 private void reject(HttpServletResponse res,int status,String code)throws IOException{
  res.setStatus(status);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("code",code,"message",code,"traceId",UUID.randomUUID().toString()));
 }
}
