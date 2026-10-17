package com.runevault.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import com.runevault.lore.SafeAssessment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LiveTransferEventPublisher implements TransferEventPublisher {
 public record Receipt(String provider, String receiptId, String providerStatus, Instant submittedAt) {}
 public record Payload(String lore, Double translationConfidence, Double riskScore, List<String> riskFlags,
   String securityMessage, String explanation, String reasonCode, Receipt receipt, Long newBalanceMinor,
   ErrorState errorState) {}
 public record Envelope(String eventId, String transactionId, Stage stage, Outcome outcome,
   Transaction.Status status, Instant timestamp, Payload payload) {}

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
  if (safe != null && safe.reasonCode() != null && !safe.reasonCode().matches("[A-Z0-9_]{1,64}"))
   throw new IllegalArgumentException("Reason must be a fixed code");
  var transaction = transactions.findById(transactionId).orElseThrow();
  String owner = transaction.getRequesterSubject();
  if (owner == null || owner.isBlank()) return; // Bridge-created records have no browser recipient.
  try { sockets.sendToSubject(owner, json.writeValueAsString(envelope(transaction, stage, outcome, safe))); }
  catch (JsonProcessingException e) { throw new IllegalStateException("Transfer event serialization failed", e); }
 }

 // Built only from committed records: no tokens, keys, signatures, counters, glyphs or raw provider output.
 static Envelope envelope(Transaction transaction, Stage stage, Outcome outcome, SafePayload safe) {
  SafeAssessment assessment = SafeAssessment.of(transaction);
  Receipt receipt = transaction.getBankProvider() == null ? null : new Receipt(transaction.getBankProvider(),
   transaction.getBankReceiptId(), transaction.getBankStatus(), transaction.getBankSubmittedAt());
  Payload payload = new Payload(assessment.lore(), assessment.translationConfidence(), assessment.riskScore(),
   assessment.riskFlags(), assessment.securityMessage(), transaction.getGeminiDecisionExplanation(),
   safe == null ? null : safe.reasonCode(), receipt, transaction.getNewBalanceMinor(),
   safe == null ? null : safe.errorState());
  return new Envelope(UUID.randomUUID().toString(), transaction.getTransactionId(), stage, outcome,
   transaction.getStatus(), Instant.now(), payload);
 }
}
