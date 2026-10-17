package com.runevault.banking;

public record BankReceipt(
    String receiptId,
    String transactionId,
    String provider,
    String status,
    long amountMinor,
    String currency,
    String destination,
    Outcome outcome,
    Reason reason,
    java.time.Instant submittedAt,
    // Source balance in cents, only when the bank confirmed it moved by exactly this amount; else null.
    Long newBalanceMinor
) {
    public enum Outcome { ACCEPTED_PENDING, COMPLETED, FAILED, UNKNOWN }
    public enum Reason {
        ACCEPTED_PENDING, CONFIRMED_COMPLETED, DEFINITE_REJECTION,
        PRE_SUBMISSION_FAILURE, SUBMISSION_UNCERTAIN, MALFORMED_RESPONSE, PROVIDER_STATUS_UNKNOWN
    }

    public BankReceipt withNewBalanceMinor(Long balance) {
        return new BankReceipt(receiptId, transactionId, provider, status, amountMinor, currency, destination,
            outcome, reason, submittedAt, balance);
    }
}
