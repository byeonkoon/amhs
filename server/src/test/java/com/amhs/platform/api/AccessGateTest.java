package com.amhs.platform.api;
import com.amhs.platform.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * What a request needs is decided by what it can break, not by whether it writes. Reads stay open;
 * every change presents a login, meets the level its path takes, and is recorded.
 */
class AccessGateTest {
 private static final String TOKEN="local-test-token-at-least-32-characters";
 private final List<AuditEntry> recorded=new ArrayList<>();
 private final AuditRecorder audit=recorded::add;

 /** A stand-in for the login store: one live session per level, by its own token. */
 private static Logins sessionsFor(Map<String,Principal> byToken){
  var logins=Mockito.mock(Logins.class);
  Mockito.when(logins.resolve(anyString())).thenAnswer(call->{
   String hash=call.getArgument(0);
   for(var e:byToken.entrySet())
    if(PrincipalResolver.sha256Hex(e.getKey()).equals(hash)) return Optional.of(e.getValue());
   return Optional.empty();
  });
  return logins;
 }
 private static final String OPERATOR_TOKEN="operator-session-at-least-32-characters";
 private static final String MANAGER_TOKEN="manager-session-at-least-32-characters";
 private static final Map<String,Principal> SESSIONS=Map.of(
  OPERATOR_TOKEN,new Principal(7L,1L,"op1",Role.OPERATOR),
  MANAGER_TOKEN,new Principal(8L,1L,"mgr1",Role.MANAGER));

 private MockHttpServletResponse call(boolean enabled,String configured,String supplied,String method,String uri)throws Exception{
  var request=new MockHttpServletRequest(method,uri);
  if(supplied!=null)request.addHeader("Authorization",supplied);
  request.addHeader("X-Amhs-Reason","line stopped");
  var response=new MockHttpServletResponse();
  new AccessGate(new ObjectMapper(),new PrincipalResolver(enabled,configured,sessionsFor(SESSIONS)),audit)
   .doFilter(request,response,(r,s)->((MockHttpServletResponse)s).getWriter().write("PASSED"));
  return response;
 }
 private MockHttpServletResponse config(boolean enabled,String configured,String supplied,String method)throws Exception{
  return call(enabled,configured,supplied,method,"/api/v1/sites/1/configuratio%6e/vehicles");
 }

 @Test void readOnlyRequestsStayAvailable()throws Exception{
  assertThat(call(false,"",null,"GET","/api/v1/sites/1/vehicles").getContentAsString()).isEqualTo("PASSED");
  assertThat(recorded).isEmpty();
 }

 /** Logging in is how somebody gets a level, so it cannot be refused for not having one. */
 @Test void loggingInDoesNotNeedToBeLoggedIn()throws Exception{
  assertThat(call(false,"",null,"POST","/api/v1/sites/1/session").getContentAsString()).isEqualTo("PASSED");
 }

 @Test void disabledOrWeakLocalTokenIsRejectedOnEncodedPath()throws Exception{
  assertThat(config(false,TOKEN,"Bearer "+TOKEN,"POST").getStatus()).isEqualTo(401);
  assertThat(config(true,"short","Bearer short","PUT").getStatus()).isEqualTo(401);
 }

 @Test void localAdminTokenPassesConfigurationChange()throws Exception{
  assertThat(config(true,TOKEN,null,"POST").getStatus()).isEqualTo(401);
  assertThat(config(true,TOKEN,"Bearer wrong","POST").getStatus()).isEqualTo(401);
  assertThat(config(true,TOKEN,"Bearer "+TOKEN,"POST").getContentAsString()).isEqualTo("PASSED");
 }

 /** Level 2 runs the work that is already configured, and configures nothing. */
 @Test void anOperatorRunsTheWorkButConfiguresNothing()throws Exception{
  String operator="Bearer "+OPERATOR_TOKEN;
  assertThat(call(true,TOKEN,operator,"POST","/api/v1/sites/1/transport-orders").getContentAsString()).isEqualTo("PASSED");
  assertThat(call(true,TOKEN,operator,"POST","/api/v1/sites/1/operation-sweep").getContentAsString()).isEqualTo("PASSED");
  assertThat(config(true,TOKEN,operator,"POST").getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,operator,"PUT","/api/v1/sites/1/configuration/system").getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,operator,"POST","/api/v1/sites/1/inventory/carriers").getStatus()).isEqualTo(403);
 }

 /** Level 3 adds what is being carried and stored, and still configures nothing. */
 @Test void aManagerReachesInventoryAndNotTheConfiguration()throws Exception{
  String manager="Bearer "+MANAGER_TOKEN;
  assertThat(call(true,TOKEN,manager,"POST","/api/v1/sites/1/inventory/carriers").getContentAsString()).isEqualTo("PASSED");
  assertThat(call(true,TOKEN,manager,"POST","/api/v1/sites/1/transport-orders").getContentAsString()).isEqualTo("PASSED");
  assertThat(config(true,TOKEN,manager,"POST").getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,manager,"POST","/api/v1/sites/1/configuration/accounts").getStatus()).isEqualTo(403);
 }

 @Test void everyChangeIsAuditedWithActorTargetResultAndReason()throws Exception{
  recorded.clear();
  config(true,TOKEN,"Bearer "+OPERATOR_TOKEN,"POST");
  assertThat(recorded).hasSize(1);
  var denied=recorded.get(0);
  assertThat(denied.result()).isEqualTo("DENIED");
  assertThat(denied.statusCode()).isEqualTo(403);
  assertThat(denied.actor().code()).isEqualTo("op1");
  assertThat(denied.target()).contains("/configuration/vehicles");
  assertThat(denied.reason()).isEqualTo("line stopped");
  assertThat(denied.traceId()).isNotBlank();
  recorded.clear();
  config(true,TOKEN,"Bearer "+TOKEN,"POST");
  assertThat(recorded).singleElement().satisfies(e->{
   assertThat(e.result()).isEqualTo("ALLOWED");
   assertThat(e.actor().role()).isEqualTo(Role.ADMIN);
  });
 }

 @Test void anonymousDenialIsAuditedToo()throws Exception{
  recorded.clear();
  config(true,TOKEN,null,"DELETE");
  assertThat(recorded).singleElement().satisfies(e->{
   assertThat(e.result()).isEqualTo("DENIED");
   assertThat(e.actor().code()).isEqualTo("anonymous");
   assertThat(e.action()).isEqualTo("DELETE");
  });
 }
}
