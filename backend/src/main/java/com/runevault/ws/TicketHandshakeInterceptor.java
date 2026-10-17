package com.runevault.ws;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {
 private static final String PREFIX = "runevault.ticket.";
 private final WebSocketTickets tickets;
 private final Set<String> origins;

 public TicketHandshakeInterceptor(WebSocketTickets tickets,
   @Value("${runevault.ws.allowed-origins:}") String allowedOrigins) {
  this.tickets = tickets;
  this.origins = Arrays.stream(allowedOrigins.split(","))
   .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
  for (String origin : origins)
   if (!origin.matches("https?://[^/]+") || origin.endsWith("/"))
    throw new IllegalArgumentException("Invalid WebSocket allowed origin");
 }

 String[] allowedOrigins() { return origins.toArray(String[]::new); }

 @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
   WebSocketHandler handler, Map<String, Object> attributes) {
  String origin = request.getHeaders().getOrigin();
  if (origin == null || !origins.contains(origin)) return false;
  String protocol = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
  if (protocol == null || !protocol.startsWith(PREFIX) || protocol.contains(",")) return false;
  WebSocketTickets.Ticket ticket = tickets.redeem(protocol.substring(PREFIX.length()));
  if (ticket == null) return false;
  attributes.put("wsTicket", ticket);
  return true;
 }

 @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
   WebSocketHandler handler, Exception exception) {}
}
