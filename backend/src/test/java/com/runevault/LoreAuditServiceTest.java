package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.lore.GeminiClient;
import com.runevault.lore.LoreAuditService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoreAuditServiceTest {
 private final GeminiClient client = mock(GeminiClient.class);
 private final LoreAuditService lore = new LoreAuditService(client, new ObjectMapper());

 private void answer(String json) {
  when(client.isConfigured()).thenReturn(true);
  when(client.generateJson(anyString(), anyString())).thenReturn(json);
 }

 @Test void malformedJsonAndMissingFieldsFailClosed() {
  answer("not JSON");
  assertThrows(Exception.class, () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
  answer("""
   {"lore":{"title":"T","text":"A"},"risk":{"level":"LOW","reasons":[]},"risk_score":10}
   """);
  assertThrows(LoreAuditService.InvalidResponse.class,
   () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
 }

 @Test void outOfRangeNumbersFailClosed() {
  answer("""
   {"lore":{"title":"T","text":"A"},"risk":{"level":"LOW","reasons":[]},
    "translation_confidence":1.1,"risk_score":10}
   """);
  assertThrows(LoreAuditService.InvalidResponse.class,
   () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
  answer("""
   {"lore":{"title":"T","text":"A"},"risk":{"level":"LOW","reasons":[]},
    "translation_confidence":0.8,"risk_score":101}
   """);
  assertThrows(LoreAuditService.InvalidResponse.class,
   () -> lore.audit("0-1-2", LoreAuditService.DICTIONARY, 50000, "acct-999"));
 }
}
