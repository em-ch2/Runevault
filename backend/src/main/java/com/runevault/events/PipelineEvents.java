package com.runevault.events;

import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import com.runevault.lore.SafeAssessment;
import com.runevault.ws.TransferEventPublisher;
import com.runevault.ws.TransferEventPublisher.ErrorState;
import com.runevault.ws.TransferEventPublisher.Outcome;
import com.runevault.ws.TransferEventPublisher.SafePayload;
import com.runevault.ws.TransferEventPublisher.Stage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * The one place committed pipeline stages are reported. Each stage is first written durably to
 * pipeline_events, then sent as a best-effort WebSocket notification. The two are independent:
 * a failure in either is logged and never rolls back, changes or repeats a transfer.
 */
@Service
public class PipelineEvents {
 private static final Logger log = LoggerFactory.getLogger(PipelineEvents.class);

 private final PipelineEventWriter writer;
 private final TransferEventPublisher publisher;
 private final TransactionRepository transactions;
 private final AuditEventRepository auditEvents;

 public PipelineEvents(PipelineEventWriter writer, TransferEventPublisher publisher,
   TransactionRepository transactions, AuditEventRepository auditEvents) {
  this.writer = writer;
  this.publisher = publisher;
  this.transactions = transactions;
  this.auditEvents = auditEvents;
 }

 /** Reports PENDING for a committed createPending() response (HTTP 201 carrying tx_id). */
 public void createdIfSuccessful(ResponseEntity<Map<String, Object>> response) {
  if (response.getStatusCode().value() == 201 && response.getBody() != null
    && response.getBody().get("tx_id") instanceof String id)
   record(id, Stage.PENDING, Outcome.PENDING, null, null, true);
 }

 public void verified(String transactionId) {
  record(transactionId, Stage.VERIFIED, Outcome.APPROVED, null, null, true);
 }

 /** A signed authorization for an existing transaction was refused; each attempt is its own event. */
 public void verificationRejected(String transactionId) {
  if (!transactions.existsById(transactionId)) return;
  String reason = auditEvents.findFirstByTransactionIdAndEventTypeOrderByIdDesc(
    transactionId, AuditEvent.Type.DEVICE_AUTHORIZATION)
   .filter(e -> e.getOutcome() == AuditEvent.Outcome.REJECTED)
   .map(e -> e.getReason().name()).orElse(null);
  record(transactionId, Stage.VERIFIED, Outcome.REJECTED, reason, ErrorState.REJECTED, false);
 }

 public void geminiDecision(String transactionId, AuditEvent.Reason reason) {
  boolean approved = reason == AuditEvent.Reason.GEMINI_APPROVED;
  record(transactionId, Stage.GEMINI, approved ? Outcome.APPROVED : Outcome.REJECTED, reason.name(),
   approved ? null : ErrorState.BLOCKED, true);
 }

 public void executing(String transactionId) {
  record(transactionId, Stage.EXECUTING, Outcome.APPROVED, null, null, true);
 }

 public void bankOutcome(Transaction saved) {
  String reason = saved.getBankReason() == null ? null : saved.getBankReason().name();
  switch (saved.getStatus()) {
   case BANK_PENDING -> record(saved.getTransactionId(), Stage.BANK_PENDING, Outcome.ACCEPTED_PENDING, reason, null, true);
   case EXECUTED -> record(saved.getTransactionId(), Stage.EXECUTED, Outcome.COMPLETED, reason, null, true);
   case FAILED -> record(saved.getTransactionId(), Stage.FAILED, Outcome.FAILED, reason, ErrorState.FAILED, true);
   case OUTCOME_UNKNOWN -> record(saved.getTransactionId(), Stage.OUTCOME_UNKNOWN, Outcome.UNKNOWN, reason,
    ErrorState.UNKNOWN, true);
   default -> log.warn("Unexpected status {} after bank outcome for {}", saved.getStatus(), saved.getTransactionId());
  }
 }

 private void record(String transactionId, Stage stage, Outcome outcome, String reasonCode,
   ErrorState errorState, boolean oncePerStage) {
  try {
   Transaction transaction = transactions.findById(transactionId).orElseThrow();
   SafeAssessment assessment = SafeAssessment.of(transaction);
   Instant now = Instant.now();
   // A stage happens once per transfer, so its ID is derived from it and a repeat is ignored.
   UUID eventId = oncePerStage
    ? UUID.nameUUIDFromBytes((transactionId + "|" + stage + "|" + outcome).getBytes(StandardCharsets.UTF_8))
    : UUID.randomUUID();
   Long latency = transaction.getCreatedAt() == null ? null
    : Math.max(0, Duration.between(transaction.getCreatedAt(), now).toMillis());
   writer.write(new PipelineEventWriter.Row(eventId, now, transactionId, stage.name(), outcome.name(),
    transaction.getStatus().name(), reasonCode, assessment.riskScore(), assessment.translationConfidence(), latency));
  } catch (RuntimeException e) {
   log.warn("Could not store pipeline event {} {} for {}", stage, outcome, transactionId, e);
  }
  try {
   publisher.publish(transactionId, stage, outcome, new SafePayload(reasonCode, errorState));
  } catch (RuntimeException e) {
   log.warn("Could not publish pipeline event {} {} for {}", stage, outcome, transactionId, e);
  }
 }
}
