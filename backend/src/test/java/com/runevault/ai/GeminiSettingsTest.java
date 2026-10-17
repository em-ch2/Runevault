package com.runevault.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiSettingsTest {

    @Test
    void configuredWhenKeyAndModelArePresent() {
        GeminiSettings settings = new GeminiSettings("secret-key", "some-model", 10);

        assertTrue(settings.isConfigured());
        assertEquals(Duration.ofSeconds(10), settings.timeout());
    }

    @Test
    void notConfiguredWhenKeyIsBlank() {
        assertFalse(new GeminiSettings("", "some-model", 10).isConfigured());
        assertFalse(new GeminiSettings("   ", "some-model", 10).isConfigured());
    }

    @Test
    void notConfiguredWhenModelIsBlank() {
        assertFalse(new GeminiSettings("secret-key", "", 10).isConfigured());
    }

    @Test
    void toStringNeverContainsTheKey() {
        GeminiSettings settings = new GeminiSettings("secret-key", "some-model", 10);

        assertFalse(settings.toString().contains("secret-key"));
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GeminiSettings("secret-key", "some-model", 0));
    }
}