package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.transactions.GeminiPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiPolicyTest {
 private final ObjectMapper mapper = new ObjectMapper();

 private com.fasterxml.jackson.databind.JsonNode assessment(String confidence, String score) throws Exception {
  return mapper.readTree("""
   {"lore_translation":"A voyage.","translation_confidence":%s,"risk_score":%s,
    "risk_flags":[],"risk_explanation":"Routine.","security_message":"Looks routine."}
   """.formatted(confidence, score));
 }

 @Test void configuredThresholdsAreInclusive() throws Exception {
  var policy = new GeminiPolicy(new BigDecimal("0.9"), new BigDecimal("20"));
  assertEquals(AuditEvent.Reason.GEMINI_APPROVED, policy.decide(assessment("0.9", "20")));
  assertEquals(AuditEvent.Reason.GEMINI_LOW_CONFIDENCE, policy.decide(assessment("0.89", "20")));
  assertEquals(AuditEvent.Reason.GEMINI_HIGH_RISK, policy.decide(assessment("0.9", "21")));
 }

 @Test void invalidThresholdConfigurationFailsStartup() {
  assertThrows(IllegalArgumentException.class,
   () -> new GeminiPolicy(new BigDecimal("1.1"), new BigDecimal("30")));
  assertThrows(IllegalArgumentException.class,
   () -> new GeminiPolicy(new BigDecimal("0.8"), new BigDecimal("-1")));
 }

 @Test void explanationsArePlainEnglishAndUseOnlyValidatedNumbers() throws Exception {
  var policy = new GeminiPolicy(new BigDecimal("0.8"), new BigDecimal("30"));
  assertEquals("Risk score 72 is above the limit of 30, so banking was blocked.",
   policy.explain(AuditEvent.Reason.GEMINI_HIGH_RISK, assessment("0.9", "72")));
  assertEquals("Translation confidence 0.5 is below the minimum of 0.8, so banking was blocked.",
   policy.explain(AuditEvent.Reason.GEMINI_LOW_CONFIDENCE, assessment("0.50", "10")));
  assertTrue(policy.explain(AuditEvent.Reason.GEMINI_UNAVAILABLE, null).contains("unavailable"));
  assertTrue(policy.explain(AuditEvent.Reason.GEMINI_INVALID_RESPONSE, null).contains("invalid"));
  assertTrue(policy.explain(AuditEvent.Reason.GEMINI_APPROVED, assessment("0.9", "10")).endsWith("may proceed."));
 }
}
