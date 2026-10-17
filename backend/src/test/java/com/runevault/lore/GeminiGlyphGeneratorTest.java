package com.runevault.lore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiGlyphGeneratorTest {
 private final GeminiClient gemini = mock(GeminiClient.class);
 private final GeminiGlyphGenerator generator = new GeminiGlyphGenerator(gemini, new ObjectMapper());

 @Test void returnsOnlyAValidatedSixGlyphSequence() {
  when(gemini.isConfigured()).thenReturn(true);
  when(gemini.generateJson(anyString(), anyString(), anyMap())).thenReturn("{\"glyphs\":\"012102\"}");

  assertEquals("0-1-2-1-0-2", generator.generate());
 }

 @Test void rejectsMalformedGeminiResponses() {
  when(gemini.isConfigured()).thenReturn(true);
  when(gemini.generateJson(anyString(), anyString(), anyMap())).thenReturn("{\"glyphs\":\"0x2102\"}");

  assertThrows(GeminiGlyphGenerator.InvalidResponse.class, generator::generate);
 }

 @Test void reportsMissingGeminiConfigurationWithoutCallingTheApi() {
  when(gemini.isConfigured()).thenReturn(false);

  assertThrows(GeminiGlyphGenerator.Unavailable.class, generator::generate);
  verify(gemini, never()).generateJson(anyString(), anyString(), anyMap());
 }
}
