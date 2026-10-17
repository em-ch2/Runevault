package com.runevault.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TransferSocketHandlerTest {
 private WebSocketSession session(String id, String subject, Instant expiresAt) {
  WebSocketSession socket = mock(WebSocketSession.class);
  Map<String, Object> attributes = new HashMap<>();
  attributes.put("wsTicket", new WebSocketTickets.Ticket(subject, Set.of("read:transactions"),
   expiresAt, Instant.now().plusSeconds(30)));
  when(socket.getId()).thenReturn(id);
  when(socket.getAttributes()).thenReturn(attributes);
  when(socket.isOpen()).thenReturn(true);
  return socket;
 }

 @Test void disconnectAndClientMessageRemoveSessions() throws Exception {
  var handler = new TransferSocketHandler(new WebSocketTickets(30, 10, Clock.systemUTC()), 2);
  try {
   var socket = session("one", "auth0|owner", Instant.now().plusSeconds(60));
   handler.afterConnectionEstablished(socket);
   assertEquals(1, handler.activeCount());
   handler.handleMessage(socket, new TextMessage("{\"approve\":true}"));
   assertEquals(0, handler.activeCount());
   verify(socket).close(CloseStatus.POLICY_VIOLATION);
   var second = session("two", "auth0|owner", Instant.now().plusSeconds(60));
   handler.afterConnectionEstablished(second);
   handler.afterConnectionClosed(second, CloseStatus.NORMAL);
   assertEquals(0, handler.activeCount());
  } finally { handler.shutdown(); }
 }

 @Test void publisherSendsOnlyToStoredRequester() throws Exception {
  var handler = new TransferSocketHandler(new WebSocketTickets(30, 10, Clock.systemUTC()), 2);
  try {
   var owner = session("owner", "auth0|owner", Instant.now().plusSeconds(60));
   var other = session("other", "auth0|other", Instant.now().plusSeconds(60));
   handler.afterConnectionEstablished(owner);
   handler.afterConnectionEstablished(other);
   TransactionRepository repository = mock(TransactionRepository.class);
   when(repository.findById("tx-one")).thenReturn(Optional.of(
    Transaction.pending("tx-one", 50000, "acct-999", "slab-01", "0-2-1", "auth0|owner")));
   var publisher = new LiveTransferEventPublisher(repository, handler, new ObjectMapper().findAndRegisterModules());
   publisher.publish("tx-one", TransferEventPublisher.Stage.VERIFIED,
    TransferEventPublisher.Outcome.APPROVED, null);
   verify(owner, timeout(1000)).sendMessage(any(TextMessage.class));
   verify(other, never()).sendMessage(any());
  } finally { handler.shutdown(); }
 }

 @Test void expiryClosesSession() throws Exception {
  var handler = new TransferSocketHandler(new WebSocketTickets(30, 10, Clock.systemUTC()), 2);
  try {
   var socket = session("short", "auth0|owner", Instant.now().plusMillis(300));
   handler.afterConnectionEstablished(socket);
   verify(socket, timeout(2000)).close(CloseStatus.POLICY_VIOLATION);
   assertEquals(0, handler.activeCount());
  } finally { handler.shutdown(); }
 }

 @Test void limitsConnectionsPerSubject() throws Exception {
  var handler = new TransferSocketHandler(new WebSocketTickets(30, 10, Clock.systemUTC()), 1);
  try {
   var first = session("first", "auth0|owner", Instant.now().plusSeconds(60));
   var second = session("second", "auth0|owner", Instant.now().plusSeconds(60));
   handler.afterConnectionEstablished(first);
   handler.afterConnectionEstablished(second);
   assertEquals(1, handler.activeCount());
   verify(second).close(CloseStatus.POLICY_VIOLATION);
  } finally { handler.shutdown(); }
 }
}
