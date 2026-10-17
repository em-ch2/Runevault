package com.runevault.lore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.Transaction;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Display-safe view of a stored Gemini assessment, shared by the dashboard API and live events.
 * Text is plain, length-capped, and never contains the approval glyph sequence.
 */
public record SafeAssessment(String lore, Double translationConfidence, Double riskScore,
  List<String> riskFlags, String riskExplanation, String securityMessage) {

 private static final ObjectMapper JSON = new ObjectMapper();
 private static final SafeAssessment NONE = new SafeAssessment(null, null, null, List.of(), null, null);
 // Anything shaped like a glyph sequence (e.g. "0-1-2", "0 - 2 - 1 - 1").
 private static final Pattern GLYPH_LIKE = Pattern.compile("(?<![0-9])[0-2](?:\\s*-\\s*[0-2]){1,63}(?![0-9])");
 private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

 public static SafeAssessment of(Transaction transaction) {
  if (transaction == null || transaction.getAuditJson() == null) return NONE;
  JsonNode audit;
  try {
   audit = JSON.readTree(transaction.getAuditJson());
  } catch (Exception e) {
   return NONE;
  }
  // Older lore-risk-v1 rows have none of these fields and show as empty.
  String glyphs = transaction.getExpectedGlyphs();
  List<String> flags = audit.path("risk_flags").isArray()
   ? StreamSupport.stream(audit.path("risk_flags").spliterator(), false)
     .filter(JsonNode::isTextual).map(f -> clean(f.asText(), 80, glyphs)).filter(f -> f != null).limit(5).toList()
   : List.of();
  return new SafeAssessment(
   clean(audit.path("lore_translation").asText(null), 2000, glyphs),
   number(audit, "translation_confidence", 1),
   number(audit, "risk_score", 100),
   flags,
   clean(audit.path("risk_explanation").asText(null), 500, glyphs),
   clean(audit.path("security_message").asText(null), 300, glyphs));
 }

 static String clean(String text, int max, String glyphs) {
  if (text == null) return null;
  String safe = CONTROL.matcher(text).replaceAll(" ");
  if (glyphs != null && !glyphs.isBlank()) safe = safe.replace(glyphs, "[sealed glyphs]");
  safe = GLYPH_LIKE.matcher(safe).replaceAll("[sealed glyphs]").strip();
  if (safe.isEmpty()) return null;
  return safe.length() <= max ? safe : safe.substring(0, max);
 }

 private static Double number(JsonNode audit, String field, double max) {
  JsonNode value = audit.path(field);
  if (!value.isNumber()) return null;
  double n = value.asDouble();
  return Double.isFinite(n) && n >= 0 && n <= max ? n : null;
 }
}
