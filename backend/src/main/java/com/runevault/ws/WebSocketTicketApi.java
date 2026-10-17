package com.runevault.ws;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@Profile("auth")
public class WebSocketTicketApi {
 private final WebSocketTickets tickets;
 public WebSocketTicketApi(WebSocketTickets tickets) { this.tickets = tickets; }

 @PostMapping("/api/ws-ticket")
 public ResponseEntity<WebSocketTickets.Issued> issue(@AuthenticationPrincipal Jwt jwt) {
  try {
   return ResponseEntity.ok().cacheControl(CacheControl.noStore())
    .header(HttpHeaders.PRAGMA, "no-cache").body(tickets.issue(jwt));
  } catch (WebSocketTickets.TicketCapacityException e) {
   throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "WebSocket ticket capacity reached");
  } catch (IllegalArgumentException e) {
   throw new ResponseStatusException(HttpStatus.FORBIDDEN, "WebSocket ticket unavailable");
  }
 }
}
