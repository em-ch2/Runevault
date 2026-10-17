package com.runevault.transactions;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {
    public VerifiedTransaction createVerifiedTransaction(
        String challengeId,
        String deviceId,
        long amountMinor,
        String destination,
        String glyphs
    ) {
        Instant now = Instant.now();

        return new VerifiedTransaction(
            challengeId,
            deviceId,
            amountMinor,
            "USD",
            destination,
            glyphs,
            TransactionStatus.VERIFIED,
            now,
            now
        );
    }
}
