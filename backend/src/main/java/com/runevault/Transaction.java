package com.runevault;

import jakarta.persistence.*;
import java.time.Instant;
import com.runevault.banking.BankReceipt;

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
 @Column(length = 3)
 private String currency;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, length = 32)
 private Status status;
 @Column(nullable = false, updatable = false)
 private Instant createdAt;
 @Column(nullable = false)
 private Instant updatedAt;

 @Column(length = 64)
 private String deviceId;
 @Column(length = 127)
 private String expectedGlyphs;
 private Instant expiresAt;
 @Column(length = 255)
 private String requesterSubject;
 private Instant executionClaimedAt;
 private Instant verifiedAt;
 @Column(columnDefinition = "text")
 private String auditJson;
 @Column(length = 64)
 private String auditSource;
 @Column(length = 64)
 private String promptVersion;
 private Instant auditedAt;

 @Column(length = 64)
 private String bankReceiptId;
 @Column(length = 64)
 private String bankStatus;
 @Column(length = 32)
 private String bankProvider;
 @Enumerated(EnumType.STRING)
 @Column(length = 32)
 private BankReceipt.Outcome bankOutcome;
 @Enumerated(EnumType.STRING)
 @Column(length = 40)
 private BankReceipt.Reason bankReason;
 private Instant bankSubmittedAt;
 private Instant bankOutcomeAt;

 public enum Status { PENDING, SIMULATED, VERIFIED, EXECUTING, BANK_PENDING, EXECUTED, FAILED, OUTCOME_UNKNOWN }


 public static Transaction pending(String id, long amount, String destination, String device, String glyphs) {
  return pending(id, amount, destination, device, glyphs, null);
 }

 public static Transaction pending(String id, long amount, String destination, String device, String glyphs,
   String requesterSubject) {
  Transaction transaction = new Transaction(id, amount, destination);
  transaction.status = Status.PENDING;
  transaction.deviceId = device;
  transaction.expectedGlyphs = glyphs;
  transaction.expiresAt = Instant.now().plusSeconds(120);
  transaction.requesterSubject = requesterSubject;
  return transaction;
 }

 public String getDeviceId() { return deviceId; }
 public String getExpectedGlyphs() { return expectedGlyphs; }
 public Instant getExpiresAt() { return expiresAt; }
 public String getRequesterSubject() { return requesterSubject; }
 public Instant getExecutionClaimedAt() { return executionClaimedAt; }
 public void claimForExecution(Instant claimedAt) {
  if (status != Status.VERIFIED || verifiedAt == null || executionClaimedAt != null || claimedAt == null)
   throw new IllegalStateException("Transaction is not eligible for execution");
  status = Status.EXECUTING;
  executionClaimedAt = claimedAt;
 }
 public Instant getVerifiedAt() { return verifiedAt; }
 public void verifyPhysicalApproval(Instant verifiedAt) {
  if (status != Status.PENDING || verifiedAt == null) throw new IllegalStateException("Transaction is not pending");
  status = Status.VERIFIED;
  this.verifiedAt = verifiedAt;
 }
 public String getAuditJson() { return auditJson; }
 public String getAuditSource() { return auditSource; }
 public String getPromptVersion() { return promptVersion; }
 public Instant getAuditedAt() { return auditedAt; }
 public String getBankReceiptId() { return bankReceiptId; }
 public String getBankStatus() { return bankStatus; }
 public String getBankProvider() { return bankProvider; }
 public BankReceipt.Outcome getBankOutcome() { return bankOutcome; }
 public BankReceipt.Reason getBankReason() { return bankReason; }
 public Instant getBankSubmittedAt() { return bankSubmittedAt; }
 public Instant getBankOutcomeAt() { return bankOutcomeAt; }
 public void recordExecution(BankReceipt receipt, Instant recordedAt) {
  if (status != Status.EXECUTING) throw new IllegalStateException("Transaction is not executing");
  if (receipt == null || receipt.outcome() == null || receipt.reason() == null || recordedAt == null
   || !transactionId.equals(receipt.transactionId()) || amountMinor != receipt.amountMinor()
   || !currency.equals(receipt.currency()) || !destination.equals(receipt.destination()))
   throw new IllegalArgumentException("Invalid bank result for transaction");
  bankReceiptId = receipt.receiptId();
  bankStatus = receipt.status();
  bankProvider = receipt.provider();
  bankOutcome = receipt.outcome();
  bankReason = receipt.reason();
  bankSubmittedAt = receipt.submittedAt();
  bankOutcomeAt = recordedAt;
  status = switch (receipt.outcome()) {
   case ACCEPTED_PENDING -> Status.BANK_PENDING;
   case COMPLETED -> Status.EXECUTED;
   case FAILED -> Status.FAILED;
   case UNKNOWN -> Status.OUTCOME_UNKNOWN;
  };
 }


 protected Transaction() {}

 public Transaction(String transactionId, long amountMinor, String destination) {
  this.transactionId = transactionId;
  this.amountMinor = amountMinor;
  this.destination = destination;
  this.currency = "USD";
  this.status = Status.SIMULATED;
  this.createdAt = Instant.now();
  this.updatedAt = createdAt;
 }

 @PreUpdate
 private void updateTimestamp() { updatedAt = Instant.now(); }

 public String getTransactionId() { return transactionId; }
 public long getAmountMinor() { return amountMinor; }
 public String getDestination() { return destination; }
 public String getCurrency() { return currency; }
 public Status getStatus() { return status; }
 public Instant getCreatedAt() { return createdAt; }
 public Instant getUpdatedAt() { return updatedAt; }
}
