package com.amhs.platform.api;
import com.amhs.platform.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AccessGateTest {
 private static final String TOKEN="local-test-token-at-least-32-characters";
 private static final String OPERATOR_TOKEN="operator-token-at-least-32-characters-long";
 private final List<AuditEntry> recorded=new ArrayList<>();
 private final AuditRecorder audit=recorded::add;
 private final AccountQuery accounts=hash->
  AccessGate.sha256Hex(OPERATOR_TOKEN).equals(hash)
   ? Optional.of(new Principal(7L,1L,"op1",Role.OPERATOR)) : Optional.empty();
 private MockHttpServletResponse call(boolean enabled,String configured,String supplied,String method,String uri)throws Exception{
  var request=new MockHttpServletRequest(method,uri);
  if(supplied!=null)request.addHeader("Authorization",supplied);
  request.addHeader("X-Amhs-Reason","line stopped");
  var response=new MockHttpServletResponse();
  new AccessGate(enabled,configured,new ObjectMapper(),accounts,audit)
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
 @Test void disabledOrWeakLocalTokenIsRejectedOnEncodedPath()throws Exception{
  assertThat(config(false,TOKEN,"Bearer "+TOKEN,"POST").getStatus()).isEqualTo(401);
  assertThat(config(true,"short","Bearer short","PUT").getStatus()).isEqualTo(401);
 }
 @Test void localAdminTokenPassesConfigurationChange()throws Exception{
  assertThat(config(true,TOKEN,null,"POST").getStatus()).isEqualTo(401);
  assertThat(config(true,TOKEN,"Bearer wrong","POST").getStatus()).isEqualTo(401);
  assertThat(config(true,TOKEN,"Bearer "+TOKEN,"POST").getContentAsString()).isEqualTo("PASSED");
 }
 @Test void operatorCannotChangeConfigurationButCanIssueOperations()throws Exception{
  assertThat(config(true,TOKEN,"Bearer "+OPERATOR_TOKEN,"POST").getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,"Bearer "+OPERATOR_TOKEN,"POST","/api/v1/sites/1/transport-orders").getContentAsString()).isEqualTo("PASSED");
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
