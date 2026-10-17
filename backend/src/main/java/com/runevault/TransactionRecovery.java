package com.runevault;

import com.runevault.transactions.TransactionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

/** Classifies abandoned execution intents; never retries a bank call. */
@Configuration
@EnableScheduling
public class TransactionRecovery {
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;
    private final long staleSeconds;
    public TransactionRecovery(TransactionRepository transactions, PlatformTransactionManager manager,
            @Value("${runevault.execution.stale-seconds:300}") long staleSeconds) {
        if (staleSeconds < 1) throw new IllegalArgumentException("Execution stale timeout must be positive");
        this.transactions=transactions; this.tx=new TransactionTemplate(manager); this.staleSeconds=staleSeconds;
    }
    @Scheduled(initialDelayString="${runevault.execution.recovery-interval-ms:60000}",
               fixedDelayString="${runevault.execution.recovery-interval-ms:60000}")
    public void reconcile() {
        Instant now=Instant.now();
        Instant cutoff=now.minusSeconds(staleSeconds);
        for (var candidate:transactions.findByStatusAndExecutionStartedAtBefore(TransactionStatus.EXECUTING,cutoff)) {
            tx.executeWithoutResult(ignored -> transactions.findForUpdate(candidate.getTransactionId()).ifPresent(t -> {
                if (t.getStatus()==TransactionStatus.EXECUTING && t.getExecutionStartedAt().isBefore(cutoff)) {
                    t.transition(TransactionStatus.EXECUTION_UNKNOWN);
                    t.reason("Execution intent expired without confirmation; manual reconciliation required, never automatic retry");
                }
            }));
        }
        for (var candidate:transactions.findByStatusAndExpiresAtBefore(TransactionStatus.PENDING,now)) {
            tx.executeWithoutResult(ignored -> transactions.findForUpdate(candidate.getTransactionId()).ifPresent(t -> {
                if (t.getStatus()==TransactionStatus.PENDING && t.getExpiresAt().isBefore(now)) t.transition(TransactionStatus.EXPIRED);
            }));
        }
    }
}
