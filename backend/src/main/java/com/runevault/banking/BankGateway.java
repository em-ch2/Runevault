package com.runevault.banking;

public interface BankGateway {
    BankReceipt transfer(
        String transactionId,
        long amountMinor,
        String currency,
        String destination
    );
}