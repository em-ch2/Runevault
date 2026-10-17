package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-permission-tests",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:dashboard-permissions-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class DashboardPermissionsHttpTest {
 private static final String BODY = """
  {"device":"slab-01","amount":50000,"dest":"acct-999","glyphs":"0-2-1-2-1","requesterSubject":"spoofed"}
  """;
 @Autowired MockMvc mvc;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @Autowired ObjectMapper mapper;
 @MockBean JwtDecoder decoder;

 @BeforeEach void prepare() {
  auditEvents.deleteAll();
  transactions.deleteAll();
  devices.deleteAll();
  token("employee", "auth0|employee", "create:transactions", "read:transactions");
  token("viewer", "auth0|viewer", "read:transactions");
  token("creator", "auth0|creator", "create:transactions");
  token("admin", "auth0|admin", "create:transactions", "read:transactions", "admin:platform");
 }

 private void token(String value, String subject, String... permissions) {
  Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of(permissions)).build();
  when(decoder.decode(value)).thenReturn(jwt);
 }

 @Test void permissionAndVerifiedSubjectControlDashboardRoutes() throws Exception {
  mvc.perform(post("/api/transactions").contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/transactions").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer viewer")
    .contentType("application/json").content(BODY))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer creator"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer employee")
    .contentType("application/json").content(BODY))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
  assertEquals("auth0|employee", transactions.findAll().get(0).getRequesterSubject());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer viewer"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].requesterSubject").value("auth0|employee"))
   .andExpect(jsonPath("$.content[0].expectedGlyphs").doesNotExist());
  mvc.perform(get("/api/admin/audit-events").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/audit-events").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk());
 }

 @Test void bridgeEndpointsStillRequireBridgeToken() throws Exception {
  mvc.perform(post("/api/device/transactions").header("Authorization", "Bearer admin")
    .contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(BODY))
   .andExpect(status().isCreated());
  assertNull(transactions.findAll().get(0).getRequesterSubject());
 }

 @Test void dashboardExposesPhysicalVerificationTime() throws Exception {
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer employee")
    .contentType("application/json").content(BODY)).andExpect(status().isCreated());
  String id = transactions.findAll().get(0).getTransactionId();
  String signed = "v1|slab-01|1|" + id + "|50000|acct-999|0-2-1-2-1";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-pico-key-for-permission-tests".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  var approval = new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999",
   "0-2-1-2-1", HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(mapper.writeValueAsString(approval)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].status").value("VERIFIED"))
   .andExpect(jsonPath("$.content[0].verifiedAt").isNotEmpty());
 }
}
