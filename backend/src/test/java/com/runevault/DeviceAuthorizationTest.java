package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import com.runevault.transactions.TransactionService;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"runevault.bridge-token=test-bridge-token-with-enough-characters"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class DeviceAuthorizationTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private static final String GLYPHS = "0-2-1-2-1";
 private static final String KEY = randomKey();
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @Autowired TransactionService transactionService;
 @Autowired DeviceRevocationService revocations;

 private static String randomKey() {
  byte[] bytes = new byte[32];
  new SecureRandom().nextBytes(bytes);
  return HexFormat.of().formatHex(bytes);
 }

 @DynamicPropertySource
 static void config(DynamicPropertyRegistry registry) {
  registry.add("runevault.pico-key", () -> KEY);
  registry.add("runevault.pico-device-id", () -> "slab-01");
  registry.add("spring.datasource.url", () -> "jdbc:h2:mem:device-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
 }

 @BeforeEach void reset() {
  auditEvents.deleteAll();
  transactions.deleteAll();
  devices.deleteAll();
 }

 private String pending() throws Exception {
  var response = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(
    Map.of("device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", GLYPHS))))
   .andExpect(status().isCreated()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
 }

 private static String signature(String key, String text) throws Exception {
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
 }

 private DeviceApi.Authorization signed(String id, long counter, long amount, String dest, String glyphs) throws Exception {
  // Independent canonical construction, rather than signing via production helper.
  String text = "v1|slab-01|" + counter + "|" + id + "|" + amount + "|" + dest + "|" + glyphs;
  return new DeviceApi.Authorization(1, "slab-01", counter, id, amount, dest, glyphs, signature(KEY, text));
 }

 private ResultActions authorize(DeviceApi.Authorization request) throws Exception {
  return mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(request)));
 }
 private void markGeminiApproved(String id) {
  var transaction = transactions.findById(id).orElseThrow();
  transaction.recordGeminiDecision(AuditEvent.Reason.GEMINI_APPROVED, null, java.time.Instant.now());
  transactions.saveAndFlush(transaction);
 }

 @Test void exactCanonicalFormat() {
  var request = new DeviceApi.Authorization(1, "slab-01", 2L, "abc123", 50000L, "acct-999", GLYPHS, "0".repeat(64));
  assertEquals("v1|slab-01|2|abc123|50000|acct-999|0-2-1-2-1", DeviceAuthorizationService.signingText(request));
 }

 @Test
 @EnabledIfEnvironmentVariable(named = "PICO_TEST_VECTOR_KEY", matches = ".+")
 void suppliedFirmwareTestVector() throws Exception {
  assertEquals("16e6adc714f04bf58c98d0ffc343b31ecd560ed418ad9d1158d6321c2021563b",
   signature(System.getenv("PICO_TEST_VECTOR_KEY"), "v1|slab-01|2|abc123|50000|acct-999|0-2-1-2-1"));
 }

 @Test void successfulAuthorizationPersists() throws Exception {
  String id = pending();
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS))
   .andExpect(status().isOk()).andExpect(jsonPath("transactionId").value(id))
   .andExpect(jsonPath("status").value("VERIFIED"))
   .andExpect(jsonPath("verifiedAt").isNotEmpty());
  var saved = transactions.findById(id).orElseThrow();
  assertEquals(Transaction.Status.VERIFIED, saved.getStatus());
  assertNotNull(saved.getVerifiedAt());
  assertFalse(saved.getVerifiedAt().isBefore(saved.getCreatedAt()));
  assertEquals(2, devices.findById("slab-01").orElseThrow().getLastCounter());
  var event = auditEvents.findAll().get(0);
  assertEquals(AuditEvent.Outcome.APPROVED, event.getOutcome());
  assertEquals(AuditEvent.Reason.APPROVED, event.getReason());
  assertEquals(id, event.getTransactionId());
  assertNotNull(event.getTimestamp());
 }

 @Test void revocationRejectsPendingCreationAuthorizationAndPriorVerifiedClaim() throws Exception {
  String verifiedId = pending();
  authorize(signed(verifiedId, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  markGeminiApproved(verifiedId);
  String pendingId = pending();
  revocations.revoke("slab-01", "auth0|admin");
  mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(
    Map.of("device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", GLYPHS))))
   .andExpect(status().is(423));
  authorize(signed(pendingId, 2, 50000, "acct-999", GLYPHS)).andExpect(status().is(423));
  mvc.perform(get("/api/device/transactions/" + pendingId + "?device=slab-01")
   .header("X-Bridge-Token", TOKEN)).andExpect(status().is(423));
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(verifiedId));
  assertEquals(Transaction.Status.VERIFIED, transactions.findById(verifiedId).orElseThrow().getStatus());
  assertNull(transactions.findById(verifiedId).orElseThrow().getExecutionClaimedAt());
  assertEquals(1, devices.findById("slab-01").orElseThrow().getLastCounter());
  assertEquals(3, auditEvents.findAll().stream().filter(e -> e.getReason() == AuditEvent.Reason.DEVICE_REVOKED).count());
 }

 @Test void bridgeCanLoadOnlyLiveAssignedPendingTransaction() throws Exception {
  String id = pending();
  String path = "/api/device/transactions/" + id + "?device=slab-01";
  mvc.perform(get(path)).andExpect(status().isUnauthorized());
  mvc.perform(get(path).header("X-Bridge-Token", "wrong")).andExpect(status().isUnauthorized());
  mvc.perform(get(path).header("X-Bridge-Token", TOKEN))
   .andExpect(status().isOk()).andExpect(jsonPath("$.tx_id").value(id))
   .andExpect(jsonPath("$.amount").value(50000)).andExpect(jsonPath("$.dest").value("acct-999"))
   .andExpect(jsonPath("$.device").value("slab-01"))
   .andExpect(jsonPath("$.status").value("PENDING"))
   .andExpect(jsonPath("$.expires_at").isNumber())
   .andExpect(jsonPath("$.expectedGlyphs").doesNotExist())
   .andExpect(jsonPath("$.sig").doesNotExist());
  mvc.perform(get("/api/device/transactions/missing?device=slab-01").header("X-Bridge-Token", TOKEN))
   .andExpect(status().isNotFound());
  mvc.perform(get("/api/device/transactions/" + id + "?device=other").header("X-Bridge-Token", TOKEN))
   .andExpect(status().isConflict());
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  mvc.perform(get(path).header("X-Bridge-Token", TOKEN)).andExpect(status().isConflict());
 }

 @Test void expiredLookupAndTamperedPayloadAreRejectedAndAudited() throws Exception {
  String id = pending();
  var transaction = transactions.findById(id).orElseThrow();
  var field = Transaction.class.getDeclaredField("expiresAt");
  field.setAccessible(true);
  field.set(transaction, java.time.Instant.now().minusSeconds(1));
  transactions.saveAndFlush(transaction);
  mvc.perform(get("/api/device/transactions/" + id + "?device=slab-01")
   .header("X-Bridge-Token", TOKEN)).andExpect(status().isGone());
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  assertEquals(AuditEvent.Reason.TRANSACTION_EXPIRED, auditEvents.findAll().get(0).getReason());
  String fresh = pending();
  var signed = signed(fresh, 2, 50000, "acct-999", GLYPHS);
  authorize(new DeviceApi.Authorization(1, "slab-01", 2L, fresh, 50001L,
   "acct-999", GLYPHS, signed.sig())).andExpect(status().isUnauthorized());
  assertEquals(Transaction.Status.PENDING, transactions.findById(fresh).orElseThrow().getStatus());
  assertTrue(auditEvents.findAll().stream().anyMatch(event -> event.getReason() == AuditEvent.Reason.INVALID_SIGNATURE));
 }

 @Test void onlyCommittedVerifiedApprovalCanBeClaimed() throws Exception {
  String id = pending();
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(id));
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  markGeminiApproved(id);
  var details = transactionService.claimPicoAuthorized(id);
  assertEquals(id, details.id());
  assertEquals(50000L, details.amountMinor());
  assertEquals("acct-999", details.destination());
  assertEquals("USD", details.currency());
  assertEquals("slab-01", details.deviceId());
  assertEquals(com.runevault.transactions.TransactionStatus.EXECUTING, details.status());
  var saved = transactions.findById(id).orElseThrow();
  assertEquals(Transaction.Status.EXECUTING, saved.getStatus());
  assertNotNull(saved.getExecutionClaimedAt());
  assertEquals(2, auditEvents.findAll().stream().filter(e -> e.getEventType() == AuditEvent.Type.EXECUTION_CLAIM).count());
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(id));
  assertEquals(3, auditEvents.findAll().stream().filter(e -> e.getEventType() == AuditEvent.Type.EXECUTION_CLAIM).count());
 }

 @Test void concurrentDuplicateApprovalsHaveOneWinner() throws Exception {
  String id = pending();
  var request = signed(id, 1, 50000, "acct-999", GLYPHS);
  var start = new CountDownLatch(1);
  try (var workers = Executors.newFixedThreadPool(2)) {
   var first = workers.submit(() -> { start.await(); return authorize(request).andReturn().getResponse().getStatus(); });
   var second = workers.submit(() -> { start.await(); return authorize(request).andReturn().getResponse().getStatus(); });
   start.countDown();
   assertEquals(java.util.Set.of(200, 409), java.util.Set.of(first.get(), second.get()));
  }
  assertEquals(1, auditEvents.findAll().stream().filter(e -> e.getOutcome() == AuditEvent.Outcome.APPROVED).count());
 }

 @Test void concurrentExecutionClaimsHaveOneWinner() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  markGeminiApproved(id);
  var start = new CountDownLatch(1);
  try (var workers = Executors.newFixedThreadPool(2)) {
   java.util.concurrent.Callable<Boolean> claim = () -> {
    start.await();
    try { transactionService.claimPicoAuthorized(id); return true; }
    catch (IllegalStateException rejected) { return false; }
   };
   var first = workers.submit(claim);
   var second = workers.submit(claim);
   start.countDown();
   assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
  }
  assertNotNull(transactions.findById(id).orElseThrow().getExecutionClaimedAt());
  assertEquals(2, auditEvents.findAll().stream().filter(e -> e.getEventType() == AuditEvent.Type.EXECUTION_CLAIM).count());
 }

 @Test void revocationAndClaimSerializeOnDeviceRow() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  markGeminiApproved(id);
  var start = new CountDownLatch(1);
  boolean claimed;
  try (var workers = Executors.newFixedThreadPool(2)) {
   var claim = workers.submit(() -> {
    start.await();
    try { transactionService.claimPicoAuthorized(id); return true; }
    catch (IllegalStateException rejected) { return false; }
   });
   var revoke = workers.submit(() -> { start.await(); revocations.revoke("slab-01", "auth0|admin"); return true; });
   start.countDown();
   claimed = claim.get();
   assertTrue(revoke.get());
  }
  assertTrue(devices.findById("slab-01").orElseThrow().isRevoked());
  var saved = transactions.findById(id).orElseThrow();
  assertEquals(claimed ? Transaction.Status.EXECUTING : Transaction.Status.VERIFIED, saved.getStatus());
  assertEquals(claimed, saved.getExecutionClaimedAt() != null);
  if (!claimed) assertTrue(auditEvents.findAll().stream()
   .anyMatch(e -> e.getEventType() == AuditEvent.Type.EXECUTION_CLAIM
    && e.getReason() == AuditEvent.Reason.DEVICE_REVOKED));
 }

 @Test void pendingHistoricalAndMissingEvidenceCannotBeClaimed() throws Exception {
  String pendingId = pending();
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized(pendingId));
  var historical = new Transaction("historical", 50000, "acct-999");
  transactions.saveAndFlush(historical);
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized("historical"));
  var missingTimestamp = Transaction.pending("missing-timestamp", 50000, "acct-999", "slab-01", GLYPHS);
  missingTimestamp.verifyPhysicalApproval(java.time.Instant.now());
  var field = Transaction.class.getDeclaredField("verifiedAt");
  field.setAccessible(true);
  field.set(missingTimestamp, null);
  transactions.saveAndFlush(missingTimestamp);
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized("missing-timestamp"));
  var missingDevice = Transaction.pending("missing-device", 50000, "acct-999", "slab-01", GLYPHS);
  missingDevice.verifyPhysicalApproval(java.time.Instant.now());
  field = Transaction.class.getDeclaredField("deviceId");
  field.setAccessible(true);
  field.set(missingDevice, null);
  transactions.saveAndFlush(missingDevice);
  assertThrows(IllegalStateException.class, () -> transactionService.claimPicoAuthorized("missing-device"));
  assertEquals(4, auditEvents.count());
 }

 @Test void invalidSignatureDoesNotChangeDeviceOrTransaction() throws Exception {
  String id = pending();
  authorize(new DeviceApi.Authorization(1, "slab-01", 2L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64)))
   .andExpect(status().isUnauthorized());
  var device = devices.findById("slab-01").orElseThrow();
  assertEquals(-1, device.getLastCounter());
  assertEquals(0, device.getFailedAttempts());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  assertEquals(AuditEvent.Reason.INVALID_SIGNATURE, auditEvents.findAll().get(0).getReason());
 }

 @Test void counterReplayAndConsumedTransactionsAreRejected() throws Exception {
  String id = pending();
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  String next = pending();
  authorize(signed(next, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(id, 3, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(next, 4, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  assertEquals(5, auditEvents.count());
  assertEquals(2, auditEvents.findAll().stream()
   .filter(event -> event.getReason() == AuditEvent.Reason.COUNTER_REPLAY).count());
 }

 @Test void fifthWrongGlyphLocksDeviceAcrossTransactions() throws Exception {
  String id = pending();
  String other = pending();
  for (int counter = 1; counter <= 5; counter++) {
   authorize(signed(id, counter, 50000, "acct-999", "0-0"))
    .andExpect(status().is(counter == 5 ? 423 : 403));
  }
  var device = devices.findById("slab-01").orElseThrow();
  assertTrue(device.isLocked(5));
  assertEquals(5, device.getLastCounter());
  authorize(signed(other, 6, 50000, "acct-999", GLYPHS)).andExpect(status().is(423));
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(6, auditEvents.findAll().stream()
   .filter(event -> event.getOutcome() == AuditEvent.Outcome.REJECTED).count());
  assertEquals(2, auditEvents.findAll().stream()
   .filter(event -> event.getReason() == AuditEvent.Reason.DEVICE_LOCKED).count());
 }

 @Test void wrongGlyphReplayDoesNotCountTwiceAndSuccessResetsAttempts() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50000, "acct-999", "0")).andExpect(status().isForbidden());
  authorize(signed(id, 1, 50000, "acct-999", "0")).andExpect(status().isConflict());
  assertEquals(1, devices.findById("slab-01").orElseThrow().getFailedAttempts());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  assertEquals(0, devices.findById("slab-01").orElseThrow().getFailedAttempts());
 }

 @Test void pendingAmountDestinationAndIdMustMatch() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50001, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(id, 2, 50000, "other", GLYPHS)).andExpect(status().isConflict());
  authorize(signed("missing", 3, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(3, auditEvents.count());
  assertTrue(auditEvents.findAll().stream()
   .allMatch(event -> event.getReason() == AuditEvent.Reason.TRANSACTION_MISMATCH));
 }

 @Test void expiredTransactionIsAudited() throws Exception {
  String id = pending();
  var transaction = transactions.findById(id).orElseThrow();
  var field = Transaction.class.getDeclaredField("expiresAt");
  field.setAccessible(true);
  field.set(transaction, java.time.Instant.now().minusSeconds(1));
  transactions.saveAndFlush(transaction);
  authorize(signed(id, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  assertEquals(AuditEvent.Reason.TRANSACTION_EXPIRED, auditEvents.findAll().get(0).getReason());
  assertEquals(1, devices.findById("slab-01").orElseThrow().getLastCounter());
 }

 @Test void auditNeverContainsSignatureOrConfiguredKey() throws Exception {
  String id = pending();
  var bad = new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64));
  authorize(bad).andExpect(status().isUnauthorized());
  var stored = mapper.writeValueAsString(auditEvents.findAll().get(0));
  assertFalse(stored.contains(KEY));
  assertFalse(stored.contains(TOKEN));
  assertFalse(stored.contains(bad.sig()));
 }

 @Test void bridgeTokenRequiredOnBothEndpoints() throws Exception {
  for (String path : new String[]{"/api/device/authorize", "/api/device/transactions"}) {
   mvc.perform(post(path).contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
   mvc.perform(post(path).header("X-Bridge-Token", "wrong").contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
  }
 }

 @Test void legacySimulatorRoutesAreUnavailableInDefaultDev() throws Exception {
  mvc.perform(post("/api/challenges").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/approvals").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
 }

 @Test void malformedPayloadRejected() throws Exception {
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
  String id = pending();
  authorize(new DeviceApi.Authorization(2, "slab-01", 1L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64)))
   .andExpect(status().isBadRequest());
  authorize(signed(id, 1, 50000, "acct-999", "0-3"))
   .andExpect(status().isBadRequest());
 }
}
