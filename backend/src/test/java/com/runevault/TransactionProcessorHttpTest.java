package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.lore.LoreAuditService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=processor-test-pico-key",
 "runevault.auto-execute=true",
 "spring.datasource.url=jdbc:h2:mem:processor-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class TransactionProcessorHttpTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository events;
 @Autowired DeviceRevocationService revocations;
 @MockBean LoreAuditService lore;
 @SpyBean com.runevault.banking.FakeBankGateway bank;

 @BeforeEach void reset() {
  events.deleteAll(); transactions.deleteAll(); devices.deleteAll();
  org.mockito.Mockito.reset(bank, lore);
 }

 private String pending() throws Exception {
  var response = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(Map.of(
    "device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", "0-1-2"))))
   .andExpect(status().isCreated()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
 }

 private String signed(String id, long counter) throws Exception {
  String text = "v1|slab-01|" + counter + "|" + id + "|50000|acct-999|0-1-2";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("processor-test-pico-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  String sig = HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  return mapper.writeValueAsString(new DeviceApi.Authorization(1, "slab-01", counter, id,
   50000L, "acct-999", "0-1-2", sig));
 }

 private void ai(String confidence, String risk) throws Exception {
  when(lore.audit(anyString(), anyString(), anyLong(), anyString()))
   .thenReturn(mapper.readTree("""
    {"lore":{"title":"Voyage","text":"Safe passage"},"risk":{"level":"LOW","reasons":[]},
     "translation_confidence":%s,"risk_score":%s}
    """.formatted(confidence, risk)));
 }

 @Test void boundaryValuesPassAndReplayCannotBankAgain() throws Exception {
  ai("0.8", "30");
  String id = pending(); String body = signed(id, 1);
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.status").value("EXECUTED"));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(body)).andExpect(status().isConflict());
  verify(bank, times(1)).transfer(id, 50000L, "USD", "acct-999");
  assertEquals(AuditEvent.Reason.GEMINI_APPROVED, transactions.findById(id).orElseThrow().getGeminiDecisionReason());
  assertEquals(1, events.findAll().stream().filter(e -> e.getEventType() == AuditEvent.Type.BANK_OUTCOME).count());
 }

 @Test void concurrentDuplicateApprovalsCallBankOnce() throws Exception {
  ai("0.9", "10");
  String id = pending(); String body = signed(id, 1);
  var start = new java.util.concurrent.CountDownLatch(1);
  try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
   java.util.concurrent.Callable<Integer> approve = () -> {
    start.await();
    return mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
     .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
   };
   var first = workers.submit(approve);
   var second = workers.submit(approve);
   start.countDown();
   assertEquals(java.util.Set.of(200, 409), java.util.Set.of(first.get(), second.get()));
  }
  verify(bank, times(1)).transfer(id, 50000L, "USD", "acct-999");
 }

 @Test void confidenceAndRiskFailuresAreDurableAndBlockBanking() throws Exception {
  ai("0.79", "30"); String low = pending();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(low, 1)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.reason").value("GEMINI_LOW_CONFIDENCE"));
  ai("0.8", "30.01"); String high = pending();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(high, 2)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.reason").value("GEMINI_HIGH_RISK"));
  assertNull(transactions.findById(low).orElseThrow().getExecutionClaimedAt());
  assertNull(transactions.findById(high).orElseThrow().getExecutionClaimedAt());
  assertEquals(2, events.findAll().stream().filter(e -> e.getEventType() == AuditEvent.Type.GEMINI_DECISION
   && e.getOutcome() == AuditEvent.Outcome.REJECTED).count());
  verifyNoInteractions(bank);
 }

 @Test void geminiFailureAndMissingFieldsBlockBanking() throws Exception {
  when(lore.audit(anyString(), anyString(), anyLong(), anyString()))
   .thenThrow(new IllegalStateException("timeout"));
  String unavailable = pending();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(unavailable, 1)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.reason").value("GEMINI_UNAVAILABLE"));
  doReturn(mapper.readTree("""
   {"lore":{"title":"T","text":"A"},"risk":{"level":"LOW","reasons":[]}}
   """)).when(lore).audit(anyString(), anyString(), anyLong(), anyString());
  String malformed = pending();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(malformed, 2)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.reason").value("GEMINI_INVALID_RESPONSE"));
  doThrow(new com.fasterxml.jackson.core.JsonParseException(null, "invalid JSON"))
   .when(lore).audit(anyString(), anyString(), anyLong(), anyString());
  String invalidJson = pending();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(invalidJson, 3)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.reason").value("GEMINI_INVALID_RESPONSE"));
  verifyNoInteractions(bank);
 }

 @Test void invalidSignatureAndRevokedDeviceNeverReachBank() throws Exception {
  String id = pending();
  var bad = mapper.readTree(signed(id, 1));
  ((com.fasterxml.jackson.databind.node.ObjectNode) bad).put("sig", "0".repeat(64));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(bad.toString())).andExpect(status().isUnauthorized());
  revocations.revoke("slab-01", "auth0|admin");
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(signed(id, 1))).andExpect(status().is(423));
  verifyNoInteractions(bank);
 }
}
