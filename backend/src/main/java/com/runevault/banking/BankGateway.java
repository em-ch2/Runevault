package com.runevault.banking;

public interface BankGateway {
    BankReceipt transfer(
        String transactionId,
        long amountMinor,
        String currency,
        String destination
    );

    /** Read an existing provider receipt only. Empty means no reliable new observation. */
    default java.util.Optional<BankReceipt> lookup(BankReceipt previous) {
        return java.util.Optional.empty();
    }
}
