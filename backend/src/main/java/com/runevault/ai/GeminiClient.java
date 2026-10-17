package com.runevault.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class GeminiClient {
    private static final String DEFAULT_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta";

    private final GeminiSettings settings;
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GeminiClient(GeminiSettings settings) {
        this(settings, DEFAULT_BASE_URL);
    }

    // Package-private so tests can point the client at a local fake server.
    GeminiClient(GeminiSettings settings, String baseUrl) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.http = HttpClient.newBuilder()
            .connectTimeout(settings.timeout())
            .build();
    }

    public String generate(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!settings.isConfigured()) {
            throw new GeminiException("Gemini is not configured");
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                    "responseMimeType", "application/json"
                )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + settings.model() + ":generateContent"))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new GeminiException("Gemini returned HTTP " + response.statusCode());
            }
            return extractText(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException("Gemini request was interrupted", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new GeminiException("Gemini request failed", e);
        }
    }

    private String extractText(String responseBody) throws IOException {
        JsonNode text = mapper.readTree(responseBody)
            .path("candidates").path(0)
            .path("content").path("parts").path(0)
            .path("text");

        if (!text.isTextual() || text.asText().isBlank()) {
            throw new GeminiException("Gemini returned no text");
        }
        return text.asText();
    }
}