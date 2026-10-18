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

    /** The source account's balance as the provider reports it, in minor units. Empty when unavailable. */
    default java.util.Optional<Long> sourceBalanceMinor() {
        return java.util.Optional.empty();
    }
}
