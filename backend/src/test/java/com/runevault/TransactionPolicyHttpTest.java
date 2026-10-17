package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.transactions.TransactionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.requests-per-minute=10000",
 "runevault.pico-key=test-policy-pico-key",
 "runevault.transaction-policy.max-amount-minor=50000",
 "runevault.transaction-policy.allowed-destinations=acct-999,harbor",
 "runevault.transaction-policy.max-incorrect-glyph-attempts=2",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:policy-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class TransactionPolicyHttpTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private static final String KEY = "test-policy-pico-key";
 private static final String GLYPHS = "0-2-1-2-1";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @Autowired TransactionService transactionService;
 @MockBean JwtDecoder decoder;

 @BeforeEach void prepare() {
  auditEvents.deleteAll();
  transactions.deleteAll();
  devices.deleteAll();
  when(decoder.decode("creator")).thenReturn(Jwt.withTokenValue("creator").header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject("auth0|creator").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of("create:transactions")).build());
 }

 private String body(long amount, String dest) throws Exception {
  return mapper.writeValueAsString(Map.of("device", "slab-01", "amount", amount,
   "dest", dest, "glyphs", GLYPHS));
 }

 private String create(String path, long amount, String dest) throws Exception {
  var request = post(path).contentType("application/json").content(body(amount, dest));
  if (path.startsWith("/api/device")) request.header("X-Bridge-Token", TOKEN);
  else request.header("Authorization", "Bearer creator");
  var response = mvc.perform(request).andExpect(status().isCreated()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
 }

 private String signature(String id, long counter, long amount, String dest, String glyphs) throws Exception {
  String signed = "v1|slab-01|" + counter + "|" + id + "|" + amount + "|" + dest + "|" + glyphs;
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
 }

 private int authorize(String id, long counter, long amount, String dest, String glyphs) throws Exception {
  var request = new DeviceApi.Authorization(1, "slab-01", counter, id, amount, dest, glyphs,
   signature(id, counter, amount, dest, glyphs));
  return mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(request)))
   .andReturn().getResponse().getStatus();
 }

 @Test void dashboardAndBridgeApplyInclusiveAmountAndDestinationRules() throws Exception {
  String dashboardId = create("/api/transactions", 50000, "acct-999");
  String bridgeId = create("/api/device/transactions", 49999, "harbor");
  assertEquals(2, transactions.count());
  for (String path : List.of("/api/transactions", "/api/device/transactions")) {
   var excess = post(path).contentType("application/json").content(body(50001, "acct-999"));
   var destination = post(path).contentType("application/json").content(body(50000, "other"));
   if (path.startsWith("/api/device")) {
    excess.header("X-Bridge-Token", TOKEN);
    destination.header("X-Bridge-Token", TOKEN);
   } else {
    excess.header("Authorization", "Bearer creator");
    destination.header("Authorization", "Bearer creator");
   }
   mvc.perform(excess).andExpect(status().isBadRequest());
   mvc.perform(destination).andExpect(status().isBadRequest());
  }
  assertEquals(2, transactions.count());
  assertEquals(4, auditEvents.count());
  assertEquals(2, auditEvents.findAll().stream().filter(e -> e.getReason() == AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED).count());
  assertEquals(2, auditEvents.findAll().stream().filter(e -> e.getReason() == AuditEvent.Reason.DESTINATION_NOT_ALLOWED).count());
  assertTrue(auditEvents.findAll().stream().allMatch(e -> e.getEventType() == AuditEvent.Type.TRANSACTION_CREATION
   && e.getTransactionId() == null));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(dashboardId));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(bridgeId));
 }

 @Test void configuredSecondWrongGlyphLocksDevice() throws Exception {
  String id = create("/api/device/transactions", 50000, "acct-999");
  assertEquals(403, authorize(id, 1, 50000, "acct-999", "0"));
  assertEquals(423, authorize(id, 2, 50000, "acct-999", "0"));
  assertEquals(423, authorize(id, 3, 50000, "acct-999", GLYPHS));
  assertTrue(devices.findById("slab-01").orElseThrow().isLocked(2));
  assertEquals(2, devices.findById("slab-01").orElseThrow().getLastCounter());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(id));
 }

 @Test void authorizationRechecksStoredTransactionPolicy() throws Exception {
  var excessive = Transaction.pending("legacy-excess", 50001, "acct-999", "slab-01", GLYPHS);
  var disallowed = Transaction.pending("legacy-destination", 50000, "other", "slab-01", GLYPHS);
  transactions.saveAll(List.of(excessive, disallowed));
  devices.save(new DeviceState("slab-01"));
  assertEquals(403, authorize(excessive.getTransactionId(), 1, 50001, "acct-999", GLYPHS));
  assertEquals(403, authorize(disallowed.getTransactionId(), 2, 50000, "other", GLYPHS));
  assertEquals(2, devices.findById("slab-01").orElseThrow().getLastCounter());
  assertTrue(transactions.findAll().stream().allMatch(t -> t.getStatus() == Transaction.Status.PENDING));
  assertEquals(2, auditEvents.count());
  assertTrue(auditEvents.findAll().stream().anyMatch(e -> e.getReason() == AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED));
  assertTrue(auditEvents.findAll().stream().anyMatch(e -> e.getReason() == AuditEvent.Reason.DESTINATION_NOT_ALLOWED));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(excessive.getTransactionId()));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(disallowed.getTransactionId()));
 }

 @Test void claimRechecksPolicyForPersistedVerifiedRows() {
  var excessive = Transaction.pending("verified-excess", 50001, "acct-999", "slab-01", GLYPHS);
  var disallowed = Transaction.pending("verified-destination", 50000, "other", "slab-01", GLYPHS);
  excessive.verifyPhysicalApproval(Instant.now());
  disallowed.verifyPhysicalApproval(Instant.now());
  transactions.saveAllAndFlush(List.of(excessive, disallowed));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(excessive.getTransactionId()));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(disallowed.getTransactionId()));
  assertTrue(transactions.findAll().stream().allMatch(t -> t.getStatus() == Transaction.Status.VERIFIED
   && t.getExecutionClaimedAt() == null));
  assertEquals(0, auditEvents.count());
 }

 @Test void legacyChallengeCannotBypassPolicy() throws Exception {
  for (String intent : List.of(
   "{\"deviceId\":\"pico-demo-1\",\"amountMinor\":50001,\"destination\":\"harbor\",\"glyphs\":\"ABCD\"}",
   "{\"deviceId\":\"pico-demo-1\",\"amountMinor\":50000,\"destination\":\"other\",\"glyphs\":\"ABCD\"}")) {
   mvc.perform(post("/api/challenges").header("X-Bridge-Token", TOKEN)
    .contentType("application/json").content(intent)).andExpect(status().isBadRequest());
  }
  assertEquals(0, transactions.count());
  assertEquals(2, auditEvents.count());
 }
}
