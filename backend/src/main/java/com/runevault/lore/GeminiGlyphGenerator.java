package com.runevault.lore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
public class GeminiGlyphGenerator {
 private static final String SYSTEM = """
   Generate an approval glyph code for a Runevault physical transaction.
   Return exactly six glyph digits, each 0, 1, or 2, with no separators.
   Respond only with JSON containing the string field "glyphs".
   """;
 private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
  "type", "OBJECT",
  "properties", Map.of("glyphs", Map.of("type", "STRING")),
  "required", List.of("glyphs"));

 private final GeminiClient gemini;
 private final ObjectMapper mapper;

 public GeminiGlyphGenerator(GeminiClient gemini, ObjectMapper mapper) {
  this.gemini = gemini;
  this.mapper = mapper;
 }

 public String generate() {
  if (!gemini.isConfigured()) throw new Unavailable("Gemini is not configured");
  String response;
  try {
   response = gemini.generateJson(SYSTEM,
    "Generate a new approval code. Request identifier: " + UUID.randomUUID(), RESPONSE_SCHEMA);
  } catch (RestClientException e) {
   throw new Unavailable("Gemini request failed", e);
  }
  try {
   JsonNode result = mapper.readTree(response);
   String glyphs = result == null ? "" : result.path("glyphs").asText();
   if (!glyphs.matches("[0-2]{6}")) throw new InvalidResponse();
   return String.join("-", glyphs.split(""));
  } catch (JsonProcessingException e) {
   throw new InvalidResponse(e);
  }
 }

 public static class Unavailable extends RuntimeException {
  public Unavailable(String message) { super(message); }
  public Unavailable(String message, Throwable cause) { super(message, cause); }
 }

 public static class InvalidResponse extends RuntimeException {
  public InvalidResponse() { super("Gemini returned an invalid glyph code"); }
  public InvalidResponse(Throwable cause) { super("Gemini returned an invalid glyph code", cause); }
 }
}
