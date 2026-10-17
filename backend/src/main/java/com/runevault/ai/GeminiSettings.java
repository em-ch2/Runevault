package com.runevault.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GeminiSettings {
    private static final Logger log = LoggerFactory.getLogger(GeminiSettings.class);

    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public GeminiSettings(
        @Value("${runevault.gemini.api-key:}") String apiKey,
        @Value("${runevault.gemini.model:}") String model,
        @Value("${runevault.gemini.timeout-seconds:10}") long timeoutSeconds
    ) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Gemini timeout must be positive");
        }
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        log.info("Gemini configured: {} (model: {})", isConfigured(), this.model);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !model.isBlank();
    }

    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public Duration timeout() { return timeout; }

    @Override
    public String toString() {
        return "GeminiSettings[model=" + model + ", configured=" + isConfigured() + "]";
    }
}