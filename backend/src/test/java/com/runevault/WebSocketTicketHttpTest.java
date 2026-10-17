package com.runevault;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-websocket-tests",
 "runevault.ws.allowed-origins=http://localhost:4200",
 "runevault.rate-limit.ws-ticket-issuance-per-window=2",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:ws-ticket-http-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class WebSocketTicketHttpTest {
 @Autowired MockMvc mvc;
 @MockBean JwtDecoder decoder;

 private void token(String value, String permission) {
  Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject("auth0|reader").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of(permission)).build();
  when(decoder.decode(value)).thenReturn(jwt);
 }

 @Test void requiresJwtAndReadPermission() throws Exception {
  token("creator", "create:transactions");
  token("reader", "read:transactions");
  mvc.perform(post("/api/ws-ticket")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/ws-ticket").header("Authorization", "Bearer creator"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/ws-ticket").header("Authorization", "Bearer reader"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.ticket").isNotEmpty())
   .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  mvc.perform(post("/api/ws-ticket").header("Authorization", "Bearer reader"))
   .andExpect(status().isOk());
  mvc.perform(post("/api/ws-ticket").header("Authorization", "Bearer reader"))
   .andExpect(status().isTooManyRequests())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
 }
}
