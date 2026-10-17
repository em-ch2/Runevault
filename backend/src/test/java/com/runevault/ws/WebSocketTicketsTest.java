package com.runevault.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.WebSocketHandler;

class WebSocketTicketsTest {
 private static class MutableClock extends Clock {
  Instant now = Instant.parse("2026-10-17T12:00:00Z");
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return now; }
 }

 private Jwt jwt(Instant expiry, String... permissions) {
  return Jwt.withTokenValue("test").header("alg", "RS256").subject("auth0|owner")
   .issuedAt(Instant.parse("2026-10-17T11:00:00Z")).expiresAt(expiry)
   .claim("permissions", List.of(permissions)).build();
 }

 @Test void expiredInvalidAndReusedTicketsFail() {
  MutableClock clock = new MutableClock();
  WebSocketTickets tickets = new WebSocketTickets(30, 2, clock);
  var issued = tickets.issue(jwt(clock.now.plusSeconds(100), "read:transactions"));
  assertNull(tickets.redeem("invalid"));
  assertEquals("auth0|owner", tickets.redeem(issued.ticket()).subject());
  assertNull(tickets.redeem(issued.ticket()));
  var expiring = tickets.issue(jwt(clock.now.plusSeconds(2), "read:transactions"));
  clock.now = clock.now.plusSeconds(3);
  assertNull(tickets.redeem(expiring.ticket()));
  assertThrows(IllegalArgumentException.class, () -> tickets.issue(jwt(clock.now.plusSeconds(30), "create:transactions")));
 }

 @Test void concurrentRedemptionHasOneWinner() throws Exception {
  MutableClock clock = new MutableClock();
  WebSocketTickets tickets = new WebSocketTickets(30, 10, clock);
  String value = tickets.issue(jwt(clock.now.plusSeconds(60), "read:transactions")).ticket();
  var pool = Executors.newFixedThreadPool(8);
  CountDownLatch start = new CountDownLatch(1);
  AtomicInteger winners = new AtomicInteger();
  try {
   var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    if (tickets.redeem(value) != null) winners.incrementAndGet();
   }));
   start.countDown();
   for (var future : futures) future.get(3, TimeUnit.SECONDS);
   assertEquals(1, winners.get());
  } finally { pool.shutdownNow(); }
 }

 @Test void disallowedOriginDoesNotConsumeTicket() {
  MutableClock clock = new MutableClock();
  WebSocketTickets tickets = new WebSocketTickets(30, 10, clock);
  String value = tickets.issue(jwt(clock.now.plusSeconds(60), "read:transactions")).ticket();
  var interceptor = new TicketHandshakeInterceptor(tickets, "https://runevault.example");
  var attributes = new java.util.HashMap<String, Object>();
  String protocol = "runevault.ticket." + value;
  var deniedRequest = new MockHttpServletRequest("GET", "/ws/transfers");
  deniedRequest.addHeader("Origin", "https://evil.example");
  deniedRequest.addHeader("Sec-WebSocket-Protocol", protocol);
  var allowedRequest = new MockHttpServletRequest("GET", "/ws/transfers");
  allowedRequest.addHeader("Origin", "https://runevault.example");
  allowedRequest.addHeader("Sec-WebSocket-Protocol", protocol);
  var denied = new ServletServerHttpRequest(deniedRequest);
  var allowed = new ServletServerHttpRequest(allowedRequest);
  var response = new ServletServerHttpResponse(new MockHttpServletResponse());
  WebSocketHandler handler = mock(WebSocketHandler.class);
  assertFalse(interceptor.beforeHandshake(denied, response, handler, attributes));
  assertTrue(interceptor.beforeHandshake(allowed, response, handler, attributes));
  assertFalse(interceptor.beforeHandshake(allowed, response, handler, new java.util.HashMap<>()));
 }
}
