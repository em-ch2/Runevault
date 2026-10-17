package com.runevault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-websocket-tests",
 "runevault.ws.allowed-origins=http://localhost:4200",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:ws-handshake-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"auth", "test"})
class WebSocketHandshakeHttpTest {
 @LocalServerPort int port;
 @MockBean JwtDecoder decoder;
 @Autowired ObjectMapper mapper;

 @Test void realHandshakeConsumesTicket() throws Exception {
  Jwt jwt = Jwt.withTokenValue("reader").header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject("auth0|reader").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
   .claim("permissions", List.of("read:transactions")).build();
  when(decoder.decode("reader")).thenReturn(jwt);
  HttpClient client = HttpClient.newHttpClient();
  var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ws-ticket"))
   .header("Authorization", "Bearer reader").POST(HttpRequest.BodyPublishers.noBody()).build();
  var response = client.send(request, HttpResponse.BodyHandlers.ofString());
  assertEquals(200, response.statusCode());
  String ticket = mapper.readTree(response.body()).path("ticket").asText();
  URI uri = URI.create("ws://localhost:" + port + "/ws/transfers");
  WebSocket socket = client.newWebSocketBuilder().header("Origin", "http://localhost:4200")
   .subprotocols("runevault.ticket." + ticket).buildAsync(uri, new WebSocket.Listener() {}).join();
  assertNotNull(socket);
  assertThrows(CompletionException.class, () -> client.newWebSocketBuilder()
   .header("Origin", "http://localhost:4200").subprotocols("runevault.ticket." + ticket)
   .buildAsync(uri, new WebSocket.Listener() {}).join());
  socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
 }
}
