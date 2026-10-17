package com.runevault;

import jakarta.persistence.*;
import com.runevault.transactions.TransactionStatus;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction {
 @Id
 @Column(nullable = false, updatable = false, length = 36)
 private String transactionId;
 @Column(nullable = false, updatable = false)
 private long amountMinor;
 @Column(nullable = false, updatable = false, length = 64)
 private String destination;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, length = 32)
 private TransactionStatus status;
 @Column(nullable = false, updatable = false)
 private Instant createdAt;
 @Column(nullable = false)
 private Instant updatedAt;

 @Column(length = 64)
 private String deviceId;
 @Column(length = 127)
 private String expectedGlyphs;
 private Instant expiresAt;

 public static Transaction pending(String id, long amount, String destination, String device, String glyphs) {
  Transaction transaction = new Transaction(id, amount, destination);
  transaction.status = TransactionStatus.PENDING;
  transaction.deviceId = device;
  transaction.expectedGlyphs = glyphs;
  transaction.expiresAt = Instant.now().plusSeconds(120);
  return transaction;
 }

 public String getDeviceId() { return deviceId; }
 public String getExpectedGlyphs() { return expectedGlyphs; }
 public Instant getExpiresAt() { return expiresAt; }
 public void verify() { transition(TransactionStatus.VERIFIED); }

 @Column(columnDefinition = "text")
 private String auditJson;
 @Column(columnDefinition = "text")
 private String receiptJson;
 private String auditSource;
 private String promptVersion;
 private String dictionaryVersion;
 @Column(columnDefinition = "text")
 private String reviewReason;
 private Instant verifiedAt;
 private Instant auditStartedAt;
 private Instant auditedAt;
 private Instant executionStartedAt;
 private Instant completedAt;

 public void transition(TransactionStatus next) {
  status = next;
  updatedAt = Instant.now();
  if (next == TransactionStatus.VERIFIED) verifiedAt = updatedAt;
  if (next == TransactionStatus.EXECUTING) executionStartedAt = updatedAt;
  if (next == TransactionStatus.SIMULATED || next == TransactionStatus.EXECUTED) completedAt = updatedAt;
 }
 public void startAudit() { auditStartedAt = Instant.now(); }
 public void audit(String json, String source, String prompt, String dictionary) {
  auditJson = json; auditSource = source; promptVersion = prompt; dictionaryVersion = dictionary;
  auditedAt = Instant.now(); transition(TransactionStatus.AUDITED);
 }
 public void reason(String reason) { reviewReason = reason; }
 public void receipt(String json) { receiptJson = json; }
 public String getAuditJson() { return auditJson; }
 public String getReceiptJson() { return receiptJson; }
 public String getAuditSource() { return auditSource; }
 public String getPromptVersion() { return promptVersion; }
 public String getDictionaryVersion() { return dictionaryVersion; }
 public String getReviewReason() { return reviewReason; }
 public Instant getVerifiedAt() { return verifiedAt; }
 public Instant getAuditStartedAt() { return auditStartedAt; }
 public Instant getAuditedAt() { return auditedAt; }
 public Instant getExecutionStartedAt() { return executionStartedAt; }
 public Instant getCompletedAt() { return completedAt; }

 protected Transaction() {}

 public Transaction(String transactionId, long amountMinor, String destination) {
  this.transactionId = transactionId;
  this.amountMinor = amountMinor;
  this.destination = destination;
  this.status = TransactionStatus.PENDING;
  this.createdAt = Instant.now();
  this.updatedAt = createdAt;
 }

 @PreUpdate
 private void updateTimestamp() { updatedAt = Instant.now(); }

 public String getTransactionId() { return transactionId; }
 public long getAmountMinor() { return amountMinor; }
 public String getDestination() { return destination; }
 public TransactionStatus getStatus() { return status; }
 public Instant getCreatedAt() { return createdAt; }
 public Instant getUpdatedAt() { return updatedAt; }
}
