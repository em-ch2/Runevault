package com.runevault.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientTest {
    private HttpServer server;
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/test-model:generateContent", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            receivedBody.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GeminiClient client(String key, long timeoutSeconds) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GeminiClient(new GeminiSettings(key, "test-model", timeoutSeconds), baseUrl);
    }

    @Test
    void returnsTextFromTheFirstCandidateAndSendsKeyAndPrompt() {
        responseBody = """
            {"candidates":[{"content":{"parts":[{"text":"{\\"ok\\":true}"}]}}]}
            """;

        String text = client("secret-key", 5).generate("hello prompt");

        assertEquals("{\"ok\":true}", text);
        assertEquals("secret-key", receivedKey.get());
        assertTrue(receivedBody.get().contains("hello prompt"));
        assertTrue(receivedBody.get().contains("application/json"));
    }

    @Test
    void nonOkStatusThrowsWithoutLeakingTheKey() {
        status = 500;
        responseBody = "{\"error\":\"boom\"}";

        GeminiException e = assertThrows(GeminiException.class,
            () -> client("secret-key", 5).generate("x"));

        assertTrue(e.getMessage().contains("500"));
        assertFalse(e.getMessage().contains("secret-key"));
    }

    @Test
    void emptyCandidatesThrow() {
        responseBody = "{\"candidates\":[]}";

        assertThrows(GeminiException.class, () -> client("secret-key", 5).generate("x"));
    }

    @Test
    void invalidJsonThrows() {
        responseBody = "this is not json";

        assertThrows(GeminiException.class, () -> client("secret-key", 5).generate("x"));
    }

    @Test
    void notConfiguredThrowsWithoutCallingTheServer() {
        assertThrows(GeminiException.class, () -> client("", 5).generate("x"));

        assertNull(receivedBody.get());
    }

    @Test
    void slowResponseTimesOut() {
        delayMillis = 3000;
        responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"late\"}]}}]}";

        assertThrows(GeminiException.class, () -> client("secret-key", 1).generate("x"));
    }
}