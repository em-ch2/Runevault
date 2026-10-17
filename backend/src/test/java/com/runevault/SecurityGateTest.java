package com.runevault;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class SecurityGateTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private static RateLimitProperties limits() {
  return new RateLimitProperties(10, 2, 2, 2, 1, 2, 2, 2);
 }
 private MockHttpServletResponse request(SecurityConfig.Gate gate, String token) throws Exception {
  var request = new MockHttpServletRequest("POST", "/api/device/authorize");
  request.setRemoteAddr("192.0.2.5");
  if (token != null) request.addHeader("X-Bridge-Token", token);
  var response = new MockHttpServletResponse();
  gate.doFilter(request, response, new MockFilterChain());
  return response;
 }

 @Test void bridgeCredentialIsCheckedBeforeItsOwnQuota() throws Exception {
  var gate = new SecurityConfig.Gate(TOKEN, new RequestQuotaLimiter(limits()), false);
  assertEquals(401, request(gate, null).getStatus());
  assertEquals(401, request(gate, "wrong").getStatus());
  assertEquals(200, request(gate, TOKEN).getStatus());
  var limited = request(gate, TOKEN);
  assertEquals(429, limited.getStatus());
  assertEquals("10", limited.getHeader("Retry-After"));
 }

 @Test void windowsResetAndEntriesExpireWithoutGrowingPastCapacity() {
  var now = new AtomicLong(0);
  var limiter = new RequestQuotaLimiter(limits(), now::get);
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:a"));
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:a"));
  assertEquals(10, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:a"));
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:b"));
  assertEquals(10, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:c"));
  // Separate category capacity cannot be exhausted by dashboard polling.
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.BRIDGE_AUTHORIZATION, "configured-bridge"));
  now.addAndGet(10_000_000_000L);
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.DASHBOARD_READ, "user:c"));
  assertEquals(0, limiter.retryAfter(RequestQuotaLimiter.Category.BRIDGE_AUTHORIZATION, "configured-bridge"));
 }

 @Test void unauthenticatedQuotaUsesDirectAddressAndIgnoresForwardedHeaders() throws Exception {
  var gate = new SecurityConfig.Gate(TOKEN, new RequestQuotaLimiter(limits()), false);
  for (int i = 0; i < 3; i++) {
   var request = new MockHttpServletRequest("GET", "/api/transactions");
   request.setRemoteAddr("192.0.2.5");
   request.addHeader("X-Forwarded-For", "198.51.100." + i);
   var response = new MockHttpServletResponse();
   gate.doFilter(request, response, new MockFilterChain());
   assertEquals(i < 2 ? 200 : 429, response.getStatus());
  }
  var other = new MockHttpServletRequest("GET", "/api/transactions");
  other.setRemoteAddr("192.0.2.6");
  var otherResponse = new MockHttpServletResponse();
  gate.doFilter(other, otherResponse, new MockFilterChain());
  assertEquals(200, otherResponse.getStatus());
  var health = new MockHttpServletRequest("GET", "/api/health");
  health.setRemoteAddr("192.0.2.5");
  var healthResponse = new MockHttpServletResponse();
  gate.doFilter(health, healthResponse, new MockFilterChain());
  assertEquals(200, healthResponse.getStatus());
 }
}
