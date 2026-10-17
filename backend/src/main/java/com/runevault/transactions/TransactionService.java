package com.runevault.transactions;

import java.time.Instant;
import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import com.runevault.Transaction;
import com.runevault.TransactionPolicy;
import com.runevault.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final AuditEventRepository auditEvents;
    private final TransactionPolicy policy;

    public TransactionService(TransactionRepository transactions, AuditEventRepository auditEvents,
                              TransactionPolicy policy) {
        this.transactions = transactions;
        this.auditEvents = auditEvents;
        this.policy = policy;
    }

    // Call this after the Pico authorization request has committed. The DB claim is
    // durable and serialized by the transaction row lock; never retry execution blindly.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerifiedTransaction claimPicoAuthorized(String id) {
        Transaction transaction = transactions.findForUpdate(id)
            .orElseThrow(() -> new IllegalStateException("Transaction not found"));
        if (transaction.getStatus() != Transaction.Status.VERIFIED
                || transaction.getVerifiedAt() == null
                || transaction.getDeviceId() == null || transaction.getDeviceId().isBlank()
                || transaction.getExpectedGlyphs() == null || transaction.getExpectedGlyphs().isBlank()
                || transaction.getExecutionClaimedAt() != null
                || !"USD".equals(transaction.getCurrency())
                || !policy.allowsAmount(transaction.getAmountMinor())
                || !policy.allowsDestination(transaction.getDestination())) {
            throw new IllegalStateException("Transaction is not eligible for execution");
        }
        Instant claimedAt = Instant.now();
        transaction.claimForExecution(claimedAt);
        transactions.saveAndFlush(transaction);
        auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.EXECUTION_CLAIM,
            transaction.getDeviceId(), transaction.getTransactionId(),
            AuditEvent.Outcome.APPROVED, AuditEvent.Reason.EXECUTION_CLAIMED));
        return new VerifiedTransaction(transaction.getTransactionId(), transaction.getDeviceId(),
            transaction.getAmountMinor(), transaction.getCurrency(), transaction.getDestination(),
            transaction.getExpectedGlyphs(), TransactionStatus.EXECUTING,
            transaction.getCreatedAt(), transaction.getUpdatedAt());
    }

}
