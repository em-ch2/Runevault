package com.runevault.ws;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TransferSocketHandler extends TextWebSocketHandler {
 private record Connection(String subject, Instant expiresAt,
   ConcurrentWebSocketSessionDecorator socket, ScheduledFuture<?> expiry) {}
 private final Map<String, Connection> connections = new ConcurrentHashMap<>();
 private final ThreadPoolExecutor outbound = new ThreadPoolExecutor(4, 8, 30,
  TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.AbortPolicy());
 private final ScheduledExecutorService expirations = Executors.newSingleThreadScheduledExecutor();
 private final WebSocketTickets tickets;
 private final int perUser;

 public TransferSocketHandler(WebSocketTickets tickets,
   @Value("${runevault.ws.max-connections-per-user:3}") int perUser) {
  if (perUser < 1 || perUser > 20) throw new IllegalArgumentException("Invalid WebSocket connection limit");
  this.tickets = tickets; this.perUser = perUser;
 }

 @Override public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
  Object value = session.getAttributes().get("wsTicket");
  if (!(value instanceof WebSocketTickets.Ticket ticket) || !ticket.tokenExpiresAt().isAfter(Instant.now())
      || connections.size() >= 1000 || connections.values().stream()
       .filter(c -> c.subject().equals(ticket.subject())).count() >= perUser) {
   session.close(CloseStatus.POLICY_VIOLATION); return;
  }
  session.setTextMessageSizeLimit(4096);
  session.setBinaryMessageSizeLimit(1024);
  var wrapped = new ConcurrentWebSocketSessionDecorator(session, 3000, 64 * 1024);
  long delay = Math.max(0, Duration.between(Instant.now(), ticket.tokenExpiresAt()).toNanos());
  ScheduledFuture<?> future = expirations.schedule(() -> close(session.getId(), CloseStatus.POLICY_VIOLATION),
   delay, TimeUnit.NANOSECONDS);
  connections.put(session.getId(), new Connection(ticket.subject(), ticket.tokenExpiresAt(), wrapped, future));
  if (!ticket.tokenExpiresAt().isAfter(Instant.now())) close(session.getId(), CloseStatus.POLICY_VIOLATION);
 }

 @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
  // This is a strictly server-to-browser feed. No commands are accepted.
  close(session.getId(), CloseStatus.POLICY_VIOLATION);
 }

 @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
  close(session.getId(), CloseStatus.POLICY_VIOLATION);
 }

 @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
  remove(session.getId());
 }

 @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
  close(session.getId(), CloseStatus.SERVER_ERROR);
 }

 private void remove(String id) {
  Connection removed = connections.remove(id);
  if (removed != null) removed.expiry().cancel(false);
 }

 private void close(String id, CloseStatus status) {
  Connection connection = connections.remove(id);
  if (connection == null) return;
  connection.expiry().cancel(false);
  try { connection.socket().close(status); } catch (IOException ignored) { }
 }

 void sendToSubject(String subject, String json) {
  for (var entry : connections.entrySet()) {
   Connection connection = entry.getValue();
   if (!connection.subject().equals(subject)) continue;
   if (!connection.expiresAt().isAfter(Instant.now())) { close(entry.getKey(), CloseStatus.POLICY_VIOLATION); continue; }
   try {
    outbound.execute(() -> {
     if (!connection.expiresAt().isAfter(Instant.now())) { close(entry.getKey(), CloseStatus.POLICY_VIOLATION); return; }
     try { connection.socket().sendMessage(new TextMessage(json)); }
     catch (Exception e) { close(entry.getKey(), CloseStatus.SERVER_ERROR); }
    });
   } catch (java.util.concurrent.RejectedExecutionException e) {
    close(entry.getKey(), CloseStatus.SERVICE_RESTARTED);
   }
  }
 }

 int activeCount() { return connections.size(); }

 @Scheduled(fixedDelay = 10000) public void cleanup() {
  tickets.purge();
  connections.forEach((id, c) -> {
   if (!c.expiresAt().isAfter(Instant.now()) || !c.socket().isOpen()) close(id, CloseStatus.NORMAL);
  });
 }

 @PreDestroy public void shutdown() {
  connections.keySet().forEach(id -> close(id, CloseStatus.SERVICE_RESTARTED));
  outbound.shutdownNow(); expirations.shutdownNow();
 }
}
