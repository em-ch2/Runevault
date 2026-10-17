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

 public AuditEvent.Reason decide(JsonNode result) {
  com.runevault.lore.LoreAuditService.validate(result);
  BigDecimal confidence = result.path("translation_confidence").decimalValue();
  BigDecimal risk = result.path("risk_score").decimalValue();
  if (confidence.compareTo(minimumConfidence) < 0) return AuditEvent.Reason.GEMINI_LOW_CONFIDENCE;
  if (risk.compareTo(maximumRisk) > 0) return AuditEvent.Reason.GEMINI_HIGH_RISK;
  return AuditEvent.Reason.GEMINI_APPROVED;
 }
}
