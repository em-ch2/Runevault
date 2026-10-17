package com.runevault.ws;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class WebSocketTickets {
 public record Ticket(String subject, Set<String> permissions, Instant tokenExpiresAt, Instant expiresAt) {}
 public record Issued(String ticket, Instant expiresAt) {}

 private final ConcurrentHashMap<String, Ticket> unused = new ConcurrentHashMap<>();
 private final SecureRandom random = new SecureRandom();
 private final Clock clock;
 private final int ttlSeconds;
 private final int maxTickets;

 @Autowired
 public WebSocketTickets(@Value("${runevault.ws.ticket-seconds:30}") int ttlSeconds,
   @Value("${runevault.ws.max-tickets:10000}") int maxTickets) {
  this(ttlSeconds, maxTickets, Clock.systemUTC());
 }

 WebSocketTickets(int ttlSeconds, int maxTickets, Clock clock) {
  if (ttlSeconds < 1 || ttlSeconds > 120 || maxTickets < 1 || maxTickets > 100000)
   throw new IllegalArgumentException("Invalid WebSocket ticket limits");
  this.ttlSeconds = ttlSeconds;
  this.maxTickets = maxTickets;
  this.clock = clock;
 }

 public synchronized Issued issue(Jwt jwt) {
  Instant now = clock.instant();
  if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
      || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now))
   throw new IllegalArgumentException("A valid access token is required");
  List<String> permissions = jwt.getClaimAsStringList("permissions");
  if (permissions == null || !permissions.contains("read:transactions"))
   throw new IllegalArgumentException("Transaction read permission is required");
  purge(now);
  if (unused.size() >= maxTickets) throw new TicketCapacityException();
  Instant expiresAt = now.plusSeconds(ttlSeconds);
  if (jwt.getExpiresAt().isBefore(expiresAt)) expiresAt = jwt.getExpiresAt();
  final Instant ticketExpiresAt = expiresAt;
  byte[] bytes = new byte[32];
  String value;
  do {
   random.nextBytes(bytes);
   value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  } while (unused.putIfAbsent(value,
   new Ticket(jwt.getSubject(), Set.copyOf(permissions), jwt.getExpiresAt(), ticketExpiresAt)) != null);
  return new Issued(value, ticketExpiresAt);
 }

 public Ticket redeem(String value) {
  if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return null;
  Ticket ticket = unused.remove(value); // atomic: only one handshake can win
  if (ticket == null || !ticket.expiresAt().isAfter(clock.instant())
      || !ticket.tokenExpiresAt().isAfter(clock.instant())
      || !ticket.permissions().contains("read:transactions")) return null;
  return ticket;
 }

 public synchronized void purge() { purge(clock.instant()); }
 private void purge(Instant now) { unused.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now)); }

 public static final class TicketCapacityException extends RuntimeException {}
}
