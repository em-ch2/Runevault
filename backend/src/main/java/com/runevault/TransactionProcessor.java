package com.runevault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.ai.*;
import com.runevault.banking.*;
import com.runevault.glyphs.GlyphDictionary;
import com.runevault.transactions.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class TransactionProcessor {
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;
    private final AiAuditor auditor;
    private final BankGateway bank;
    private final TransactionService service;
    private final ReviewPolicy policy;
    private final ObjectMapper mapper;

    public TransactionProcessor(TransactionRepository transactions, PlatformTransactionManager manager,
            AiAuditor auditor, BankGateway bank, TransactionService service, ReviewPolicy policy, ObjectMapper mapper) {
        this.transactions = transactions; this.auditor = auditor; this.bank = bank;
        this.service = service; this.policy = policy; this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private <T> T locked(String id, Function<Transaction, T> operation) {
        return tx.execute(ignored -> operation.apply(transactions.findForUpdate(id).orElseThrow()));
    }

    public boolean verifyLegacy(String id, TransactionApi.Intent intent) {
        return locked(id, t -> {
            if (t.getStatus() != TransactionStatus.PENDING) return false;
            if (t.getExpiresAt() == null || !t.getExpiresAt().isAfter(Instant.now())) {
                t.transition(TransactionStatus.EXPIRED); return false;
            }
            if (t.getAmountMinor() != intent.amountMinor() || !t.getDestination().equals(intent.destination())
                || !t.getDeviceId().equals(intent.deviceId()) || !t.getExpectedGlyphs().equals(intent.glyphs())) return false;
            t.verify();
            transactions.saveAndFlush(t);
            return true;
        });
    }

    public void expire(String id) {
        locked(id, t -> {
            if (t.getStatus() == TransactionStatus.PENDING && !t.getExpiresAt().isAfter(Instant.now()))
                t.transition(TransactionStatus.EXPIRED);
            return null;
        });
    }

    public Map<String, Object> process(String id) {
        // Both callers must have committed authorization. Never suspend a caller's locks across I/O.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Processing must run outside an authorization transaction");
        VerifiedTransaction verified = locked(id, t -> {
            if (t.getStatus() != TransactionStatus.VERIFIED || t.getAuditStartedAt() != null) return null;
            t.startAudit();
            return service.createVerifiedTransaction(id, t.getDeviceId(), t.getAmountMinor(), t.getDestination(), t.getExpectedGlyphs());
        });
        if (verified != null) {
            AuditResult result;
            try { result = auditor.audit(verified); }
            catch (RuntimeException e) { result = AuditResult.unavailable(); }
            if (result == null) result = AuditResult.unavailable();
            AuditResult audit = result;
            locked(id, t -> {
                t.audit(json(audit), audit.source(), AuditPrompt.VERSION, GlyphDictionary.VERSION);
                return null;
            });
        }
        // A committed AUDITED or APPROVED state can resume without repeating AI or banking.
        locked(id, t -> {
            if (t.getStatus() == TransactionStatus.AUDITED) {
                var decision = policy.decide(read(t.getAuditJson(), AuditResult.class), t.getAmountMinor());
                t.reason(decision.reason()); t.transition(decision.status());
            }
            return null;
        });
        VerifiedTransaction execution = locked(id, t -> {
            if (t.getStatus() != TransactionStatus.APPROVED) return null;
            t.transition(TransactionStatus.EXECUTING);
            return service.createVerifiedTransaction(id, t.getDeviceId(), t.getAmountMinor(), t.getDestination(), t.getExpectedGlyphs());
        });
        if (execution != null) {
            try {
                BankReceipt receipt = bank.transfer(execution.id(), execution.amountMinor(), execution.currency(), execution.destination());
                if (receipt == null || !id.equals(receipt.transactionId()) || receipt.amountMinor() != execution.amountMinor()
                    || !execution.currency().equals(receipt.currency()) || !execution.destination().equals(receipt.destination())
                    || !("SIMULATED".equals(receipt.status()) || "EXECUTED".equals(receipt.status())))
                    throw new IllegalStateException("Unconfirmed bank result");
                locked(id, t -> {
                    t.receipt(json(receipt));
                    t.transition(TransactionStatus.valueOf(receipt.status()));
                    return null;
                });
            } catch (BankRejectedException e) {
                fail(id, TransactionStatus.FAILED, "Bank rejected the transfer without execution");
            } catch (RuntimeException e) {
                fail(id, TransactionStatus.EXECUTION_UNKNOWN, "Bank outcome could not be confirmed; do not retry automatically");
            }
        }
        return response(transactions.findById(id).orElseThrow());
    }

    private void fail(String id, TransactionStatus status, String reason) {
        locked(id, t -> { t.transition(status); t.reason(reason); return null; });
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot persist transaction result", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read transaction result", e); }
    }
    private Map<String,Object> response(Transaction t) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("transactionId", t.getTransactionId());
        result.put("status", t.getStatus().name());
        result.put("signatureVerified", t.getVerifiedAt() != null);
        if (t.getAuditJson() != null) result.put("analysis", read(t.getAuditJson(), AuditResult.class));
        if (t.getReceiptJson() != null) result.put("receipt", read(t.getReceiptJson(), BankReceipt.class));
        if (t.getReviewReason() != null) result.put("reviewReason", t.getReviewReason());
        return result;
    }
}
