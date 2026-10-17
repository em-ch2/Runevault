package com.runevault.transactions;

import java.time.Instant;

public record Transaction(
    String id,
    String deviceId,
    long amountMinor,
    String currency,
    String destination,
    String glyphs,
    TransactionStatus status,
    Instant createdAt,
    Instant updatedAt
) {}