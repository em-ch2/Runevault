package com.runevault.banking;

public record BankReceipt(
    String receiptId,
    String transactionId,
    String provider,
    String status,
    long amountMinor,
    String currency,
    String destination
) {}