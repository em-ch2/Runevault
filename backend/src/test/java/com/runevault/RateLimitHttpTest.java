package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-rate-limits",
 "runevault.rate-limit.dashboard-reads=2",
 "runevault.rate-limit.transaction-creation=3",
 "runevault.rate-limit.bridge-authorization=1",
 "runevault.rate-limit.bridge-other=3",
 "runevault.rate-limit.admin-operations=2",
 "runevault.rate-limit.unauthenticated=3",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:rate-limit-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class RateLimitHttpTest {
 private static final String BRIDGE = "test-bridge-token-with-enough-characters";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @MockBean JwtDecoder decoder;

 private void token(String value, String subject, String... permissions) {
  when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of(permissions)).build());
 }

 @Test void dashboardPollingAndUsersCannotExhaustBridgeApprovalQuota() throws Exception {
  token("reader-a", "auth0|a", "read:transactions", "create:transactions");
  token("reader-b", "auth0|b", "read:transactions");
  token("admin-a", "auth0|admin", "admin:platform");
  when(decoder.decode("invalid")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid token"));
  mvc.perform(get("/api/health")).andExpect(status().isOk());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer reader-a"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer reader-a"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer reader-a"))
   .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
  mvc.perform(get("/api/health")).andExpect(status().isOk());
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer reader-a")
   .contentType("application/json").content(mapper.writeValueAsString(Map.of(
    "device", "slab-01", "amount", 100, "dest", "acct-999", "glyphs", "0"))))
   .andExpect(status().isCreated());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer reader-b"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/admin/status").header("Authorization", "Bearer admin-a"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/admin/status").header("Authorization", "Bearer admin-a"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/admin/status").header("Authorization", "Bearer admin-a"))
   .andExpect(status().isTooManyRequests());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer invalid"))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", "wrong")
   .contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized());

  var pending = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", BRIDGE)
   .contentType("application/json").content(mapper.writeValueAsString(Map.of(
    "device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", "0-2-1-2-1"))))
   .andExpect(status().isCreated()).andReturn();
  String id = mapper.readTree(pending.getResponse().getContentAsString()).get("tx_id").asText();
  String signed = "v1|slab-01|1|" + id + "|50000|acct-999|0-2-1-2-1";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-pico-key-for-rate-limits".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  var approval = new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999", "0-2-1-2-1",
   HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", BRIDGE)
   .contentType("application/json").content(mapper.writeValueAsString(approval)))
   .andExpect(status().isOk()).andExpect(jsonPath("status").value("VERIFIED"));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", BRIDGE)
   .contentType("application/json").content(mapper.writeValueAsString(approval)))
   .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
 }
}
