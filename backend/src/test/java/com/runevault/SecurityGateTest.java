package com.runevault;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityGateTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private MockHttpServletResponse request(SecurityConfig.Gate gate, String token) throws Exception {
  var request = new MockHttpServletRequest("POST","/api/approvals");
  // Leave servletPath empty, as MockMvc does by default.
  if (token != null) request.addHeader("X-Bridge-Token", token);
  var response = new MockHttpServletResponse();
  gate.doFilter(request,response,new MockFilterChain());
  return response;
 }
 @Test void missingAndWrongTokensRejected() throws Exception {
  var gate = new SecurityConfig.Gate(TOKEN, 10, true);
  assertEquals(401,request(gate,null).getStatus());
  assertEquals(401,request(gate,"wrong").getStatus());
 }
 @Test void validTokenAccepted() throws Exception {
  assertEquals(200,request(new SecurityConfig.Gate(TOKEN,10,true),TOKEN).getStatus());
 }
 @Test void limitsRequests() throws Exception {
  var gate = new SecurityConfig.Gate(TOKEN,1,true);
  assertEquals(200,request(gate,TOKEN).getStatus());
  var response = request(gate,TOKEN);
  assertEquals(429,response.getStatus());
  assertEquals("60",response.getHeader("Retry-After"));
 }
}
