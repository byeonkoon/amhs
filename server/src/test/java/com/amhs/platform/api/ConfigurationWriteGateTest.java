package com.amhs.platform.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
class ConfigurationWriteGateTest {
 private static final String TOKEN="local-test-token-at-least-32-characters";
 private MockHttpServletResponse request(boolean enabled,String configured,String supplied,String method)throws Exception{
  var request=new MockHttpServletRequest(method,"/api/v1/sites/1/configuratio%6e/vehicles");
  if(supplied!=null)request.addHeader("Authorization",supplied);
  var response=new MockHttpServletResponse();
  new ConfigurationWriteGate(enabled,configured,new ObjectMapper()).doFilter(request,response,(r,s)->s.getWriter().write("PASSED"));
  return response;
 }
 @Test void defaultDisabledAndWeakTokenDenyEncodedPath()throws Exception{
  assertThat(request(false,TOKEN,"Bearer "+TOKEN,"POST").getStatus()).isEqualTo(403);
  assertThat(request(true,"short","Bearer short","PUT").getStatus()).isEqualTo(403);
 }
 @Test void validTokenIsRequired()throws Exception{
  assertThat(request(true,TOKEN,null,"POST").getStatus()).isEqualTo(401);
  assertThat(request(true,TOKEN,"Bearer wrong","POST").getStatus()).isEqualTo(401);
  assertThat(request(true,TOKEN,"Bearer "+TOKEN,"POST").getContentAsString()).isEqualTo("PASSED");
 }
 @Test void readOnlyRequestsStayAvailable()throws Exception{
  assertThat(request(false,"",null,"GET").getContentAsString()).isEqualTo("PASSED");
 }
}
