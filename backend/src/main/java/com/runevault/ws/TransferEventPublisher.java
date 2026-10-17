package com.runevault.ws;

/** Internal, best-effort notification after the underlying state change commits. */
public interface TransferEventPublisher {
 enum Stage { PENDING, VERIFIED, GEMINI, EXECUTING, BANK_PENDING, EXECUTED, FAILED, OUTCOME_UNKNOWN }
 enum Outcome { PENDING, APPROVED, REJECTED, ACCEPTED_PENDING, COMPLETED, FAILED, UNKNOWN }
 enum ErrorState { REJECTED, BLOCKED, FAILED, UNKNOWN }

 /**
  * The only values a caller supplies: a fixed server reason code and an error state. Every other
  * payload field (status, scores, lore, receipt, balance) is read from the committed transaction row.
  */
 record SafePayload(String reasonCode, ErrorState errorState) {}

 void publish(String transactionId, Stage stage, Outcome outcome, SafePayload payload);
}
