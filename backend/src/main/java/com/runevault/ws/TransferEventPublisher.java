package com.runevault.ws;

/** Internal, best-effort notification after the underlying state change commits. */
public interface TransferEventPublisher {
 enum Stage { PENDING, VERIFIED, GEMINI, EXECUTING, BANK_PENDING, EXECUTED, FAILED, OUTCOME_UNKNOWN }
 enum Outcome { PENDING, APPROVED, REJECTED, ACCEPTED_PENDING, COMPLETED, FAILED, UNKNOWN }
 record SafePayload(Double translationConfidence, Double riskScore, String securityCode) {}

 void publish(String transactionId, Stage stage, Outcome outcome, SafePayload payload);
}
