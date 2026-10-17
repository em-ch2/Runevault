package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.banking.BankReceipt;
import com.runevault.events.PipelineEventWriter;
import com.runevault.events.PipelineEvents;
import com.runevault.lore.GeminiGlyphGenerator;
import com.runevault.lore.LoreAuditService;
import com.runevault.ws.TransferEventPublisher;
import com.runevault.ws.TransferEventPublisher.ErrorState;
import com.runevault.ws.TransferEventPublisher.Outcome;
import com.runevault.ws.TransferEventPublisher.SafePayload;
import com.runevault.ws.TransferEventPublisher.Stage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=pipeline-events-test-key",
 "runevault.auto-execute=true",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:pipeline-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class PipelineEventsHttpTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate jdbc;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @Autowired PipelineEvents events;
 @MockBean JwtDecoder jwtDecoder;
 @MockBean LoreAuditService lore;
 @MockBean GeminiGlyphGenerator glyphs;
 @MockBean TransferEventPublisher publisher;
 @SpyBean PipelineEventWriter writer;
 @SpyBean com.runevault.banking.FakeBankGateway bank;
 private long counter;

 private record Row(String stage, String outcome, String status, String reason, Double risk, Double confidence, Long latency) {}

 @BeforeEach void reset() {
  auditEvents.deleteAll(); transactions.deleteAll(); devices.deleteAll();
  jdbc.update("DELETE FROM pipeline_events");
  org.mockito.Mockito.reset(lore, publisher, writer, bank, glyphs);
  counter = 0;
 }

 private void ai(String confidence, String risk) throws Exception {
  when(lore.audit(anyString(), anyString(), anyLong(), anyString())).thenReturn(mapper.readTree("""
   {"lore_translation":"Safe passage.","translation_confidence":%s,"risk_score":%s,
    "risk_flags":["known destination"],"risk_explanation":"Routine.","security_message":"Looks routine."}
   """.formatted(confidence, risk)));
 }

 private String bridgePending() throws Exception {
  var response = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(Map.of(
    "device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", "0-1-2"))))
   .andExpect(status().isCreated()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
 }

 // Signs signedGlyphs but sends 0-1-2, so a mismatch is a tampered packet.
 private String sign(String id, String signedGlyphs) throws Exception {
  long c = ++counter;
  String text = "v1|slab-01|" + c + "|" + id + "|50000|acct-999|" + signedGlyphs;
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("pipeline-events-test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  String sig = HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  return mapper.writeValueAsString(new DeviceApi.Authorization(1, "slab-01", c, id, 50000L, "acct-999", "0-1-2", sig));
 }

 private ResultActions authorize(String body) throws Exception {
  return mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(body));
 }

 private List<Row> rows(String id) {
  return jdbc.query("SELECT stage, outcome, status, reason_code, risk_score, translation_confidence, latency_ms"
    + " FROM pipeline_events WHERE transaction_id = ?",
   (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
    (Double) rs.getObject(5), (Double) rs.getObject(6), (Long) rs.getObject(7)), id);
 }

 private Row row(String id, String stage, String outcome) {
  return rows(id).stream().filter(r -> r.stage().equals(stage) && r.outcome().equals(outcome)).findFirst().orElseThrow();
 }

 @Test void approvedTransferRecordsAndPublishesEveryCommittedStageInOrder() throws Exception {
  ai("0.9", "10");
  String id = bridgePending();
  authorize(sign(id, "0-1-2")).andExpect(status().isOk()).andExpect(jsonPath("$.execution.status").value("EXECUTED"));

  InOrder order = inOrder(publisher);
  order.verify(publisher).publish(id, Stage.PENDING, Outcome.PENDING, new SafePayload(null, null));
  order.verify(publisher).publish(id, Stage.VERIFIED, Outcome.APPROVED, new SafePayload(null, null));
  order.verify(publisher).publish(id, Stage.GEMINI, Outcome.APPROVED, new SafePayload("GEMINI_APPROVED", null));
  order.verify(publisher).publish(id, Stage.EXECUTING, Outcome.APPROVED, new SafePayload(null, null));
  order.verify(publisher).publish(id, Stage.EXECUTED, Outcome.COMPLETED, new SafePayload("CONFIRMED_COMPLETED", null));

  assertEquals(5, rows(id).size());
  Row gemini = row(id, "GEMINI", "APPROVED");
  assertEquals("VERIFIED", gemini.status());
  assertEquals(10.0, gemini.risk());
  assertEquals(0.9, gemini.confidence());
  Row executed = row(id, "EXECUTED", "COMPLETED");
  assertEquals("EXECUTED", executed.status());
  assertNotNull(executed.latency());
  assertTrue(executed.latency() >= 0);
  assertTrue(transactions.findById(id).orElseThrow().getGeminiDecisionExplanation()
   .startsWith("Risk score 10 is within the limit of 30"));
 }

 @Test void dashboardCreatedTransferIsReportedAsPending() throws Exception {
  when(glyphs.generate()).thenReturn("0-1-2-0-1-2");
  var response = mvc.perform(post("/api/transactions")
   .with(jwt().jwt(j -> j.subject("auth0|alice")).authorities(new SimpleGrantedAuthority("PERM_create:transactions")))
   .contentType("application/json").content(mapper.writeValueAsString(Map.of(
    "device", "slab-01", "amount", 50000, "dest", "acct-999"))))
   .andExpect(status().isCreated()).andReturn();
  String id = mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
  verify(publisher).publish(id, Stage.PENDING, Outcome.PENDING, new SafePayload(null, null));
  assertEquals("PENDING", row(id, "PENDING", "PENDING").status());
 }

 @Test void highRiskIsBlockedWithCodeAndPersistedExplanation() throws Exception {
  ai("0.9", "72");
  String id = bridgePending();
  authorize(sign(id, "0-1-2")).andExpect(jsonPath("$.execution.reason").value("GEMINI_HIGH_RISK"));
  verify(publisher).publish(id, Stage.GEMINI, Outcome.REJECTED, new SafePayload("GEMINI_HIGH_RISK", ErrorState.BLOCKED));
  verify(publisher, never()).publish(eq(id), eq(Stage.EXECUTING), any(), any());
  Row gemini = row(id, "GEMINI", "REJECTED");
  assertEquals("GEMINI_HIGH_RISK", gemini.reason());
  assertEquals(72.0, gemini.risk());
  assertEquals("Risk score 72 is above the limit of 30, so banking was blocked.",
   transactions.findById(id).orElseThrow().getGeminiDecisionExplanation());
  verifyNoInteractions(bank);
 }

 @Test void geminiFailuresBlockWithoutScoresOrBanking() throws Exception {
  when(lore.audit(anyString(), anyString(), anyLong(), anyString())).thenThrow(new IllegalStateException("timeout"));
  String unavailable = bridgePending();
  authorize(sign(unavailable, "0-1-2")).andExpect(jsonPath("$.execution.reason").value("GEMINI_UNAVAILABLE"));
  Row row = row(unavailable, "GEMINI", "REJECTED");
  assertEquals("GEMINI_UNAVAILABLE", row.reason());
  assertNull(row.risk());
  assertNull(row.confidence());
  assertTrue(transactions.findById(unavailable).orElseThrow().getGeminiDecisionExplanation().contains("unavailable"));

  doReturn(mapper.readTree("{\"lore_translation\":\"Missing every score.\"}"))
   .when(lore).audit(anyString(), anyString(), anyLong(), anyString());
  String malformed = bridgePending();
  authorize(sign(malformed, "0-1-2")).andExpect(jsonPath("$.execution.reason").value("GEMINI_INVALID_RESPONSE"));
  verify(publisher).publish(malformed, Stage.GEMINI, Outcome.REJECTED,
   new SafePayload("GEMINI_INVALID_RESPONSE", ErrorState.BLOCKED));
  verifyNoInteractions(bank);
 }

 @Test void tamperedAndReplayedApprovalsAreRecordedPerAttemptWithReasons() throws Exception {
  ai("0.9", "10");
  String id = bridgePending();
  authorize(sign(id, "2-2-2")).andExpect(status().isUnauthorized());
  verify(publisher).publish(id, Stage.VERIFIED, Outcome.REJECTED, new SafePayload("INVALID_SIGNATURE", ErrorState.REJECTED));
  verifyNoInteractions(lore);

  String body = sign(id, "0-1-2");
  authorize(body).andExpect(status().isOk());
  authorize(body).andExpect(status().isConflict());
  verify(publisher).publish(id, Stage.VERIFIED, Outcome.REJECTED, new SafePayload("COUNTER_REPLAY", ErrorState.REJECTED));
  assertEquals(2, rows(id).stream().filter(r -> r.stage().equals("VERIFIED") && r.outcome().equals("REJECTED")).count());
  assertEquals(1, rows(id).stream().filter(r -> r.stage().equals("EXECUTED")).count());
  verify(bank, times(1)).transfer(id, 50000L, "USD", "acct-999");
 }

 @Test void unknownTransactionIdsAreNotRecorded() throws Exception {
  authorize(sign("does-not-exist", "0-1-2")).andExpect(status().is4xxClientError());
  assertTrue(rows("does-not-exist").isEmpty());
  verify(publisher, never()).publish(eq("does-not-exist"), any(), any(), any());
 }

 @Test void bankRejectionAndUnknownOutcomeAreReportedAndNeverRetried() throws Exception {
  ai("0.9", "10");
  String failed = bridgePending();
  // The receipt must name the same transaction, so build it from the call's arguments.
  doAnswer(inv -> new BankReceipt(null, inv.getArgument(0), "fake", "REJECTED", 50000, "USD", "acct-999",
   BankReceipt.Outcome.FAILED, BankReceipt.Reason.DEFINITE_REJECTION, Instant.now(), null))
   .when(bank).transfer(anyString(), anyLong(), anyString(), anyString());
  authorize(sign(failed, "0-1-2")).andExpect(jsonPath("$.execution.status").value("FAILED"));
  verify(publisher).publish(failed, Stage.FAILED, Outcome.FAILED, new SafePayload("DEFINITE_REJECTION", ErrorState.FAILED));

  doThrow(new IllegalStateException("connection reset")).when(bank).transfer(anyString(), anyLong(), anyString(), anyString());
  String unknown = bridgePending();
  authorize(sign(unknown, "0-1-2")).andExpect(jsonPath("$.execution.status").value("OUTCOME_UNKNOWN"));
  verify(publisher).publish(unknown, Stage.OUTCOME_UNKNOWN, Outcome.UNKNOWN,
   new SafePayload("SUBMISSION_UNCERTAIN", ErrorState.UNKNOWN));
  verify(bank, times(1)).transfer(eq(unknown), anyLong(), anyString(), anyString());
 }

 @Test void eventFailuresNeverBlockOrRepeatTheTransfer() throws Exception {
  ai("0.9", "10");
  doThrow(new IllegalStateException("socket down")).when(publisher).publish(any(), any(), any(), any());
  doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(writer).write(any());
  String id = bridgePending();
  authorize(sign(id, "0-1-2")).andExpect(status().isOk()).andExpect(jsonPath("$.execution.status").value("EXECUTED"));
  assertEquals(Transaction.Status.EXECUTED, transactions.findById(id).orElseThrow().getStatus());
  verify(bank, times(1)).transfer(id, 50000L, "USD", "acct-999");
 }

 @Test void repeatedStageReportsAreStoredOnce() throws Exception {
  String id = bridgePending();
  events.verified(id);
  events.verified(id);
  assertEquals(1, rows(id).stream().filter(r -> r.stage().equals("VERIFIED")).count());
 }

 @Test void metricsCountApprovalsAndBlocksAndRequireReadPermission() throws Exception {
  ai("0.9", "10");
  authorize(sign(bridgePending(), "0-1-2")).andExpect(status().isOk());
  ai("0.9", "72");
  authorize(sign(bridgePending(), "0-1-2")).andExpect(status().isOk());
  String tampered = bridgePending();
  authorize(sign(tampered, "2-2-2")).andExpect(status().isUnauthorized());

  mvc.perform(get("/api/metrics/pipeline?minutes=5")
    .with(jwt().authorities(new SimpleGrantedAuthority("PERM_read:transactions"))))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.approved").value(0))
   .andExpect(jsonPath("$.blocked").value(0))
   .andExpect(jsonPath("$.source").value("pipeline-events-user"))
   .andExpect(jsonPath("$.minutes.length()").value(5));
  mvc.perform(get("/api/metrics/pipeline").with(jwt().authorities(new SimpleGrantedAuthority("PERM_create:transactions"))))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/metrics/pipeline")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/metrics/pipeline?minutes=0")
    .with(jwt().authorities(new SimpleGrantedAuthority("PERM_read:transactions"))))
   .andExpect(status().isBadRequest());
 }
}
