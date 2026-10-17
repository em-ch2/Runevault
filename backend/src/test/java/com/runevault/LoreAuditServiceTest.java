package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.lore.GeminiClient;
import com.runevault.lore.LoreAuditService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoreAuditServiceTest {
 private final GeminiClient client = mock(GeminiClient.class);
 private final LoreAuditService lore = new LoreAuditService(client, new ObjectMapper());

 private static final String VALID = """
  {"lore_translation":"The forge is lit to begin the great exchange.","translation_confidence":0.9,
   "risk_score":10,"risk_flags":["known destination"],"risk_explanation":"Matches earlier transfers.",
   "security_message":"This transfer looks routine."}
  """;

 private void answer(String json) {
  when(client.isConfigured()).thenReturn(true);
  when(client.generateJson(anyString(), anyString(), any())).thenReturn(json);
 }

 private void assertInvalid(String json) {
  answer(json);
  assertThrows(LoreAuditService.InvalidResponse.class,
   () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
 }

 @SuppressWarnings("unchecked")
 @Test void validAnswerPassesAndRequestsTheResponseSchema() throws Exception {
  answer(VALID);
  var result = lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999");
  assertEquals(10, result.path("risk_score").asInt());
  ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
  verify(client).generateJson(anyString(), anyString(), schema.capture());
  assertEquals(java.util.List.of("lore_translation", "translation_confidence", "risk_score",
   "risk_flags", "risk_explanation", "security_message"), schema.getValue().get("required"));
 }

 @Test void malformedJsonAndMissingFieldsFailClosed() {
  answer("not JSON");
  assertThrows(Exception.class, () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
  assertInvalid("""
   {"lore_translation":"A","translation_confidence":0.9,"risk_score":10}
   """);
  assertInvalid(VALID.replace("\"security_message\":\"This transfer looks routine.\"", "\"security_message\":\" \""));
 }

 @Test void outOfRangeOrWrongTypedNumbersFailClosed() {
  assertInvalid(VALID.replace("0.9", "1.1"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":101"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":10.5"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":\"10\""));
 }

 @Test void tooManyOrBlankFlagsFailClosed() {
  assertInvalid(VALID.replace("[\"known destination\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]"));
  assertInvalid(VALID.replace("[\"known destination\"]", "[\"\"]"));
 }

 @Test void unconfiguredGeminiFailsWithoutCallingIt() {
  when(client.isConfigured()).thenReturn(false);
  assertThrows(IllegalStateException.class,
   () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
  verify(client, never()).generateJson(anyString(), anyString(), any());
 }
}
