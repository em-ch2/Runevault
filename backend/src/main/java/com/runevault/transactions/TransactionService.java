package com.runevault.transactions;

import java.time.Instant;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class TransactionService {
    private final TransactionRepository transactions;

    public TransactionService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    // Call this after the Pico authorization request has committed. The DB claim is
    // durable and serialized by the transaction row lock; never retry execution blindly.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerifiedTransaction claimPicoAuthorized(String id) {
        Transaction transaction = transactions.findForUpdate(id)
            .orElseThrow(() -> new IllegalStateException("Transaction not found"));
        if (transaction.getStatus() != Transaction.Status.SIMULATED
                || transaction.getDeviceId() == null || transaction.getExpiresAt() == null
                || transaction.getExecutionClaimedAt() != null) {
            throw new IllegalStateException("Transaction is not eligible for execution");
        }
        transaction.claimForExecution();
        transactions.saveAndFlush(transaction);
        Instant now = Instant.now();
        return new VerifiedTransaction(transaction.getTransactionId(), transaction.getDeviceId(),
            transaction.getAmountMinor(), "USD", transaction.getDestination(),
            transaction.getExpectedGlyphs(), TransactionStatus.VERIFIED,
            transaction.getCreatedAt(), now);
    }

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
