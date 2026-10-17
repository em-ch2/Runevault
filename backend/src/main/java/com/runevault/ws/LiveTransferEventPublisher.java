package com.runevault.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.TransactionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LiveTransferEventPublisher implements TransferEventPublisher {
 public record Payload(Double translationConfidence, Double riskScore, String securityCode,
   String receiptId, String providerStatus) {}
 public record Envelope(String eventId, String transactionId, Stage stage, Outcome outcome,
   Instant timestamp, Payload payload) {}

 private final TransactionRepository transactions;
 private final TransferSocketHandler sockets;
 private final ObjectMapper json;
 public LiveTransferEventPublisher(TransactionRepository transactions, TransferSocketHandler sockets, ObjectMapper json) {
  this.transactions = transactions; this.sockets = sockets; this.json = json;
 }

 @Override public void publish(String transactionId, Stage stage, Outcome outcome, SafePayload safe) {
  if (TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Publish only after the transaction commits");
  if (transactionId == null || stage == null || outcome == null) throw new IllegalArgumentException("Incomplete transfer event");
  var transaction = transactions.findById(transactionId).orElseThrow();
  String owner = transaction.getRequesterSubject();
  if (owner == null || owner.isBlank()) return; // Bridge-created records have no browser recipient.
  if (safe != null) {
   if (safe.translationConfidence() != null && (!Double.isFinite(safe.translationConfidence())
       || safe.translationConfidence() < 0 || safe.translationConfidence() > 1)) throw new IllegalArgumentException("Invalid confidence");
   if (safe.riskScore() != null && (!Double.isFinite(safe.riskScore())
       || safe.riskScore() < 0 || safe.riskScore() > 100)) throw new IllegalArgumentException("Invalid risk");
   if (safe.securityCode() != null && !safe.securityCode().matches("[A-Z0-9_]{1,64}"))
    throw new IllegalArgumentException("Security message must be a fixed code");
  }
  Payload payload = new Payload(safe == null ? null : safe.translationConfidence(),
   safe == null ? null : safe.riskScore(), safe == null ? null : safe.securityCode(),
   transaction.getBankReceiptId(), transaction.getBankStatus());
  Envelope event = new Envelope(UUID.randomUUID().toString(), transactionId, stage, outcome, Instant.now(), payload);
  try { sockets.sendToSubject(owner, json.writeValueAsString(event)); }
  catch (JsonProcessingException e) { throw new IllegalStateException("Transfer event serialization failed", e); }
 }
}
