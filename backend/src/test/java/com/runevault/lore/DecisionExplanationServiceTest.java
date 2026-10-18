package com.runevault.lore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.ApprovalPolicy;
import com.runevault.AuditEvent;
import com.runevault.Transaction;
import com.runevault.transactions.GeminiPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionExplanationServiceTest {
 private static final String FACTORS = """
  ["Physical authorization: recorded.","Deterministic policy: thresholds met."]""";
 private final ObjectMapper mapper = new ObjectMapper();
 private GeminiClient gemini;
 private DecisionExplanationService service;

 @BeforeEach void prepare() {
  gemini = mock(GeminiClient.class);
  when(gemini.isConfigured()).thenReturn(true);
  service = new DecisionExplanationService(gemini, new GeminiPolicy(new BigDecimal("0.8"), new BigDecimal("30")),
   new ApprovalPolicy(true), mapper);
 }

 private static Transaction approved(String id) {
  Transaction tx = Transaction.pending(id, 50000, "acct-999", "slab-01", "0-2-1-2-1", "auth0|maker");
  tx.verifyPhysicalApproval(Instant.now());
  tx.recordGeminiDecision(AuditEvent.Reason.GEMINI_APPROVED, null, "Within policy.", Instant.now());
  return tx;
 }

 private static String answer(String summary, String factors, String next, String limitations) {
  return "{\"summary\":" + summary + ",\"key_factors\":" + factors + ",\"required_next_step\":" + next
   + ",\"limitations\":" + limitations + "}";
 }

 @Test void answersAreBoundedTypedAndLabelledOrRejected() throws Exception {
  Transaction tx = approved("bounds");
  String ok = answer("\"Recorded.\"", FACTORS, "\"Wait.\"", "[]");
  assertEquals("gemini", service.validated(mapper.readTree(ok), tx).source());
  for (String bad : new String[] {
   answer("\"" + "x".repeat(601) + "\"", FACTORS, "\"Wait.\"", "[]"),
   answer("\"Recorded.\"", "[]", "\"Wait.\"", "[]"),
   answer("\"Recorded.\"", "[" + "\"CFO approval: a.\",".repeat(5) + "\"CFO approval: b.\"]", "\"Wait.\"", "[]"),
   answer("\"Recorded.\"", FACTORS, "\"Wait.\"", "[\"a\",\"b\",\"c\",\"d\",\"e\"]"),
   answer("\"Recorded.\"", FACTORS, "7", "[]"),
   answer("\"   \"", FACTORS, "\"Wait.\"", "[]"),
   answer("\"It will definitely be approved.\"", FACTORS, "\"Wait.\"", "[]"),
   answer("\"Recorded.\"", FACTORS, "\"Split it into two transfers.\"", "[]"),
   ok.replace("}", ",\"approved\":true}"),
   "[]"})
   assertThrows(DecisionExplanationService.InvalidExplanation.class,
    () -> service.validated(mapper.readTree(bad), tx), bad);
 }

 @Test void glyphsAndControlCharactersAreScrubbedFromAcceptedText() throws Exception {
  var result = service.validated(mapper.readTree(answer("\"Seal 0-2-1-2-1 ok\\u0007\"", FACTORS, "\"Wait.\"", "[]")),
   approved("scrub"));
  assertFalse(result.summary().contains("0-2-1"));
  assertFalse(result.summary().contains("\u0007"));
 }

 @Test void changedEvidenceIsExplainedAgainButUnchangedEvidenceIsNot() {
  when(gemini.generateJson(anyString(), anyString(), anyMap()))
   .thenReturn(answer("\"Recorded.\"", FACTORS, "\"Wait.\"", "[]"));
  Transaction tx = approved("cache");
  service.explain(tx);
  service.explain(tx);
  verify(gemini, times(1)).generateJson(anyString(), anyString(), anyMap());
  tx.claimForApprovedExecution("auth0|cfo", Instant.now());
  service.explain(tx);
  verify(gemini, times(2)).generateJson(anyString(), anyString(), anyMap());
 }

 @Test void unconfiguredGeminiUsesTheRecordedReasonWithoutCallingIt() {
  when(gemini.isConfigured()).thenReturn(false);
  var result = service.explain(approved("offline"));
  assertEquals("recorded-policy-reason", result.source());
  assertEquals("Within policy.", result.summary());
  verify(gemini, never()).generateJson(anyString(), anyString(), anyMap());
 }
}
