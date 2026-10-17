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

@SpringBootTest(properties = {"runevault.bridge-token=test-bridge-token-with-enough-characters", "runevault.requests-per-minute=10000"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class DeviceAuthorizationTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private static final String GLYPHS = "0-2-1-2-1";
 private static final String KEY = randomKey();
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired DeviceAuthorizationService authorization;
 @Autowired org.springframework.transaction.PlatformTransactionManager manager;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.runevault.banking.FakeBankGateway bank;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.runevault.ai.FakeAiAuditor auditor;

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
   .andExpect(jsonPath("status").value("SIMULATED"));
  assertEquals(com.runevault.transactions.TransactionStatus.SIMULATED, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(2, devices.findById("slab-01").orElseThrow().getLastCounter());
  org.mockito.Mockito.verify(bank).transfer(id,50000,"USD","acct-999");
  org.mockito.Mockito.verify(auditor).audit(org.mockito.ArgumentMatchers.argThat(t -> t.id().equals(id) && t.glyphs().equals(GLYPHS)));
  assertNotNull(transactions.findById(id).orElseThrow().getReceiptJson());
 }

 @Test void invalidSignatureDoesNotChangeDeviceOrTransaction() throws Exception {
  String id = pending();
  authorize(new DeviceApi.Authorization(1, "slab-01", 2L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64)))
   .andExpect(status().isUnauthorized());
  var device = devices.findById("slab-01").orElseThrow();
  assertEquals(-1, device.getLastCounter());
  assertEquals(0, device.getFailedAttempts());
  assertEquals(com.runevault.transactions.TransactionStatus.PENDING, transactions.findById(id).orElseThrow().getStatus());
  org.mockito.Mockito.verifyNoInteractions(bank, auditor);
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
  org.mockito.Mockito.verify(bank,org.mockito.Mockito.times(1)).transfer(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
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
  org.mockito.Mockito.verifyNoInteractions(bank, auditor);
  assertTrue(device.isLocked());
  assertEquals(5, device.getLastCounter());
  authorize(signed(other, 6, 50000, "acct-999", GLYPHS)).andExpect(status().is(423));
  assertEquals(com.runevault.transactions.TransactionStatus.PENDING, transactions.findById(id).orElseThrow().getStatus());
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
  org.mockito.Mockito.verifyNoInteractions(bank, auditor);
  assertEquals(com.runevault.transactions.TransactionStatus.PENDING, transactions.findById(id).orElseThrow().getStatus());
 }

 @Test void bridgeTokenRequiredOnBothEndpoints() throws Exception {
  for (String path : new String[]{"/api/device/authorize", "/api/device/transactions"}) {
   mvc.perform(post(path).contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
   mvc.perform(post(path).header("X-Bridge-Token", "wrong").contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
  }
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

 @Test void expiredRequestConsumesCounterButCannotReachAuditOrBank() throws Exception {
  String id = pending();
  new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(ignored -> {
   var t = transactions.findForUpdate(id).orElseThrow();
   org.springframework.test.util.ReflectionTestUtils.setField(t, "expiresAt", java.time.Instant.now().minusSeconds(1));
  });
  authorize(signed(id,1,50000,"acct-999",GLYPHS)).andExpect(status().isConflict());
  assertEquals(com.runevault.transactions.TransactionStatus.EXPIRED,transactions.findById(id).orElseThrow().getStatus());
  assertEquals(1,devices.findById("slab-01").orElseThrow().getLastCounter());
  org.mockito.Mockito.verifyNoInteractions(bank,auditor);
 }

 @Test void physicalAuthorizationRequiringReviewNeverCallsBank() throws Exception {
  org.mockito.Mockito.doReturn(com.runevault.ai.AuditResult.unavailable()).when(auditor).audit(org.mockito.ArgumentMatchers.any());
  String id = pending();
  authorize(signed(id,1,50000,"acct-999",GLYPHS)).andExpect(status().isOk())
   .andExpect(jsonPath("status").value("APPROVAL_PENDING"));
  assertEquals(com.runevault.transactions.TransactionStatus.APPROVAL_PENDING,transactions.findById(id).orElseThrow().getStatus());
  assertEquals(1,devices.findById("slab-01").orElseThrow().getLastCounter());
  org.mockito.Mockito.verifyNoInteractions(bank);
 }

 @Test void concurrentPhysicalApprovalsConsumeTransactionOnce() throws Exception {
  String id=pending();
  var request=signed(id,1,50000,"acct-999",GLYPHS);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var first=pool.submit(()->authorization.authorize(request));
   var second=pool.submit(()->authorization.authorize(request));
   var codes=java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value(),second.get(10,java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value());
   assertTrue(codes.contains(200)); assertTrue(codes.contains(409));
  }
  org.mockito.Mockito.verify(bank,org.mockito.Mockito.times(1)).transfer(id,50000,"USD","acct-999");
 }

 @Test void failedVerifiedSaveRollsBackCounterAndTransactionTogether() throws Exception {
  String id=pending();
  var request=signed(id,1,50000,"acct-999",GLYPHS);
  org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
   .when(transactions).saveAndFlush(org.mockito.ArgumentMatchers.any(Transaction.class));
  assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->authorization.authorize(request));
  assertEquals(-1,devices.findById("slab-01").orElseThrow().getLastCounter());
  assertEquals(com.runevault.transactions.TransactionStatus.PENDING,transactions.findById(id).orElseThrow().getStatus());
  org.mockito.Mockito.verifyNoInteractions(bank,auditor);
 }

 @Test void wrongDeviceCannotReachAuditOrBank() throws Exception {
  String id=pending();
  String text="v1|other-device|1|"+id+"|50000|acct-999|"+GLYPHS;
  authorize(new DeviceApi.Authorization(1,"other-device",1L,id,50000L,"acct-999",GLYPHS,signature(KEY,text)))
   .andExpect(status().isUnauthorized());
  org.mockito.Mockito.verifyNoInteractions(bank,auditor);
 }
}
