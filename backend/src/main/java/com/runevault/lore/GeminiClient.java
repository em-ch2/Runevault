package com.runevault.lore;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GeminiClient {
 private final RestClient http;
 private final String model;
 private final boolean configured;

 public GeminiClient(@Value("${runevault.gemini.api-key:}") String apiKey,
   @Value("${runevault.gemini.model}") String model) {
  var factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(Duration.ofSeconds(5));
  factory.setReadTimeout(Duration.ofSeconds(20));
  this.http = RestClient.builder()
    .baseUrl("https://generativelanguage.googleapis.com/v1beta")
    .defaultHeader("x-goog-api-key", apiKey)
    .requestFactory(factory)
    .build();
  this.model = model;
  this.configured = !apiKey.isBlank();
 }

 public boolean isConfigured() { return configured; }

 /** Sends a system prompt and user prompt, returns the model's JSON text. */
 public String generateJson(String system, String user) {
  Map<String, Object> body = Map.of(
    "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
    "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.2));
  JsonNode response = http.post()
    .uri("/models/{model}:generateContent", model)
    .body(body)
    .retrieve()
    .body(JsonNode.class);
  return response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
 }
}