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
  PrincipalResolver.sha256Hex(OPERATOR_TOKEN).equals(hash)
   ? Optional.of(new Principal(7L,1L,"op1",Role.OPERATOR)) : Optional.empty();
 private final AccountQuery engineers=hash->
  PrincipalResolver.sha256Hex(ENGINEER_TOKEN).equals(hash)
   ? Optional.of(new Principal(8L,1L,"eng1",Role.ENGINEER)) : Optional.empty();
 private static final String ENGINEER_TOKEN="engineer-token-at-least-32-characters-long";
 private MockHttpServletResponse call(boolean enabled,String configured,String supplied,String method,String uri)throws Exception{
  return call(enabled,configured,supplied,method,uri,accounts);
 }
 private MockHttpServletResponse call(boolean enabled,String configured,String supplied,String method,String uri,
                                      AccountQuery directory)throws Exception{
  var request=new MockHttpServletRequest(method,uri);
  if(supplied!=null)request.addHeader("Authorization",supplied);
  request.addHeader("X-Amhs-Reason","line stopped");
  var response=new MockHttpServletResponse();
  new AccessGate(new ObjectMapper(),new PrincipalResolver(enabled,configured,directory),audit)
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

 /**
  * Configuring a vehicle and rewiring the track are different jobs, so they stop sharing a
  * level: the engineer does the first and is refused the second.
  */
 @Test void engineerConfiguresResourcesButNotTheTrackOptionsOrAccounts()throws Exception{
  String engineer="Bearer "+ENGINEER_TOKEN;
  assertThat(call(true,TOKEN,engineer,"POST","/api/v1/sites/1/configuration/vehicles",engineers).getContentAsString()).isEqualTo("PASSED");
  assertThat(call(true,TOKEN,engineer,"POST","/api/v1/sites/1/configuration/equipment",engineers).getContentAsString()).isEqualTo("PASSED");
  assertThat(call(true,TOKEN,engineer,"PUT","/api/v1/sites/1/configuration/topology/nodes/1",engineers).getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,engineer,"PUT","/api/v1/sites/1/configuration/system",engineers).getStatus()).isEqualTo(403);
  assertThat(call(true,TOKEN,engineer,"POST","/api/v1/sites/1/configuration/accounts",engineers).getStatus()).isEqualTo(403);
  // Running the work stays open to anyone from OPERATOR up.
  assertThat(call(true,TOKEN,engineer,"POST","/api/v1/sites/1/operation-sweep",engineers).getContentAsString()).isEqualTo("PASSED");
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
