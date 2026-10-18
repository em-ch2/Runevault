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
 // Maker-checker: who released the transfer, when, and whether their token proved MFA.
 @Column(length = 255)
 private String approverSubject;
 private Instant approvedAt;
 private Boolean approvalStepUp;
 private Instant verifiedAt;
 @Column(columnDefinition = "text")
 private String auditJson;
 @Column(length = 64)
 private String auditSource;
 @Column(length = 64)
 private String promptVersion;
 private Instant auditedAt;
 @Enumerated(EnumType.STRING)
 @Column(length = 40)
 private AuditEvent.Reason geminiDecisionReason;
 private Instant geminiDecisionAt;
 // Fixed, backend-written explanation of the decision; never model output.
 @Column(length = 300)
 private String geminiDecisionExplanation;

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
 // Source balance confirmed by the bank after the transfer, in cents; null when not reliably known.
 private Long newBalanceMinor;

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
 public void claimForApprovedExecution(String approver, boolean stepUp, Instant claimedAt) {
  if (approver == null || approver.isBlank() || approver.equals(requesterSubject))
   throw new IllegalStateException("The approver must be someone other than the requester");
  claimForExecution(claimedAt);
  approverSubject = approver;
  approvedAt = claimedAt;
  approvalStepUp = stepUp;
 }
 public String getApproverSubject() { return approverSubject; }
 public Instant getApprovedAt() { return approvedAt; }
 public Boolean getApprovalStepUp() { return approvalStepUp; }
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
 public AuditEvent.Reason getGeminiDecisionReason() { return geminiDecisionReason; }
 public Instant getGeminiDecisionAt() { return geminiDecisionAt; }
 public String getGeminiDecisionExplanation() { return geminiDecisionExplanation; }
 public void recordGeminiDecision(AuditEvent.Reason reason, String json, Instant at) {
  recordGeminiDecision(reason, json, null, at);
 }
 public void recordGeminiDecision(AuditEvent.Reason reason, String json, String explanation, Instant at) {
  if (status != Status.VERIFIED || verifiedAt == null || geminiDecisionAt != null || reason == null || at == null)
   throw new IllegalStateException("Gemini decision is not eligible");
  geminiDecisionReason = reason;
  geminiDecisionAt = at;
  geminiDecisionExplanation = explanation == null ? null : explanation.substring(0, Math.min(300, explanation.length()));
  if (json != null) {
   auditJson = json;
   auditSource = "gemini";
   promptVersion = com.runevault.lore.LoreAuditService.PROMPT_VERSION;
   auditedAt = at;
  }
 }
 public String getBankReceiptId() { return bankReceiptId; }
 public String getBankStatus() { return bankStatus; }
 public String getBankProvider() { return bankProvider; }
 public BankReceipt.Outcome getBankOutcome() { return bankOutcome; }
 public BankReceipt.Reason getBankReason() { return bankReason; }
 public Instant getBankSubmittedAt() { return bankSubmittedAt; }
 public Instant getBankOutcomeAt() { return bankOutcomeAt; }
 public Long getNewBalanceMinor() { return newBalanceMinor; }
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
  newBalanceMinor = receipt.newBalanceMinor();
  status = switch (receipt.outcome()) {
   case ACCEPTED_PENDING -> Status.BANK_PENDING;
   case COMPLETED -> Status.EXECUTED;
   case FAILED -> Status.FAILED;
   case UNKNOWN -> Status.OUTCOME_UNKNOWN;
  };
 }

 public boolean reconcileBankOutcome(BankReceipt receipt, Instant recordedAt) {
  if ((status != Status.BANK_PENDING && status != Status.OUTCOME_UNKNOWN)
    || bankReceiptId == null || receipt == null || recordedAt == null
    || !bankReceiptId.equals(receipt.receiptId()) || !bankProvider.equals(receipt.provider())
    || !transactionId.equals(receipt.transactionId()) || amountMinor != receipt.amountMinor()
    || !currency.equals(receipt.currency()) || !destination.equals(receipt.destination())
    || receipt.outcome() == null || receipt.reason() == null)
   throw new IllegalStateException("Receipt is not eligible for reconciliation");
  Status next = switch (receipt.outcome()) {
   case ACCEPTED_PENDING -> Status.BANK_PENDING;
   case COMPLETED -> Status.EXECUTED;
   case FAILED -> Status.FAILED;
   case UNKNOWN -> Status.OUTCOME_UNKNOWN;
  };
  if (status == next && bankOutcome == receipt.outcome()
    && java.util.Objects.equals(bankStatus, receipt.status())) return false;
  status = next;
  bankStatus = receipt.status();
  bankOutcome = receipt.outcome();
  bankReason = receipt.reason();
  bankOutcomeAt = recordedAt;
  if (receipt.newBalanceMinor() != null) newBalanceMinor = receipt.newBalanceMinor();
  return true;
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
