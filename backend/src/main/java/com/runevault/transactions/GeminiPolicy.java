package com.runevault.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.AuditEvent;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiPolicy {
 private final BigDecimal minimumConfidence;
 private final BigDecimal maximumRisk;

 public GeminiPolicy(@Value("${runevault.gemini.minimum-translation-confidence:0.8}") BigDecimal minimumConfidence,
   @Value("${runevault.gemini.maximum-risk-score:30}") BigDecimal maximumRisk) {
  if (minimumConfidence == null || minimumConfidence.compareTo(BigDecimal.ZERO) < 0
    || minimumConfidence.compareTo(BigDecimal.ONE) > 0 || maximumRisk == null
    || maximumRisk.compareTo(BigDecimal.ZERO) < 0
    || maximumRisk.compareTo(new BigDecimal("100")) > 0)
   throw new IllegalArgumentException("Invalid Gemini policy thresholds");
  this.minimumConfidence = minimumConfidence;
  this.maximumRisk = maximumRisk;
 }

 public BigDecimal minimumConfidence() { return minimumConfidence; }
 public BigDecimal maximumRisk() { return maximumRisk; }

 public AuditEvent.Reason decide(JsonNode result) {
  com.runevault.lore.LoreAuditService.validate(result);
  BigDecimal confidence = result.path("translation_confidence").decimalValue();
  BigDecimal risk = result.path("risk_score").decimalValue();
  if (confidence.compareTo(minimumConfidence) < 0) return AuditEvent.Reason.GEMINI_LOW_CONFIDENCE;
  if (risk.compareTo(maximumRisk) > 0) return AuditEvent.Reason.GEMINI_HIGH_RISK;
  return AuditEvent.Reason.GEMINI_APPROVED;
 }

 /** Plain-English, backend-written reason for a decision. Uses only validated numbers, never model text. */
 public String explain(AuditEvent.Reason reason, JsonNode result) {
  return switch (reason) {
   case GEMINI_APPROVED -> "Risk score " + number(result, "risk_score") + " is within the limit of "
    + plain(maximumRisk) + " and translation confidence " + number(result, "translation_confidence")
    + " meets the minimum of " + plain(minimumConfidence) + ", so the transfer may proceed.";
   case GEMINI_HIGH_RISK -> "Risk score " + number(result, "risk_score") + " is above the limit of "
    + plain(maximumRisk) + ", so banking was blocked.";
   case GEMINI_LOW_CONFIDENCE -> "Translation confidence " + number(result, "translation_confidence")
    + " is below the minimum of " + plain(minimumConfidence) + ", so banking was blocked.";
   case GEMINI_INVALID_RESPONSE ->
    "Gemini returned an incomplete or invalid assessment, so banking was blocked.";
   case GEMINI_UNAVAILABLE ->
    "Gemini could not assess this transfer because it was unavailable or timed out, so banking was blocked.";
   default -> "This transfer was blocked by policy.";
  };
 }

 private static String number(JsonNode result, String field) {
  return result != null && result.path(field).isNumber() ? plain(result.path(field).decimalValue()) : "unknown";
 }

 private static String plain(BigDecimal value) {
  return value.stripTrailingZeros().toPlainString();
 }
}
