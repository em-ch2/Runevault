package com.runevault.transactions;

import java.time.Instant;
import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import com.runevault.Transaction;
import com.runevault.TransactionPolicy;
import com.runevault.TransactionRepository;
import com.runevault.DeviceStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final AuditEventRepository auditEvents;
    private final TransactionPolicy policy;
    private final DeviceStateRepository devices;
    private final ClaimRejectionAuditService rejections;

    public TransactionService(TransactionRepository transactions, AuditEventRepository auditEvents,
                              TransactionPolicy policy, DeviceStateRepository devices,
                              ClaimRejectionAuditService rejections) {
        this.transactions = transactions;
        this.auditEvents = auditEvents;
        this.policy = policy;
        this.devices = devices;
        this.rejections = rejections;
    }

    private IllegalStateException rejected(String deviceId, String id, AuditEvent.Reason reason) {
        rejections.record(deviceId, id, reason);
        return new IllegalStateException("Transaction is not eligible for execution");
    }

    // Call this after the Pico authorization request has committed. The DB claim is
    // durable and serialized by device lock, then transaction lock; never retry execution blindly.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerifiedTransaction claimPicoAuthorized(String id) {
        String assignedDeviceId = transactions.findAssignedDeviceId(id)
            .orElseThrow(() -> rejected(null, id, AuditEvent.Reason.EXECUTION_INELIGIBLE));
        if (assignedDeviceId.isBlank())
            throw rejected(null, id, AuditEvent.Reason.EXECUTION_INELIGIBLE);
        var device = devices.findForUpdate(assignedDeviceId)
            .orElseThrow(() -> rejected(assignedDeviceId, id, AuditEvent.Reason.EXECUTION_INELIGIBLE));
        if (device.isRevoked()) throw rejected(assignedDeviceId, id, AuditEvent.Reason.DEVICE_REVOKED);
        Transaction transaction = transactions.findForUpdate(id)
            .orElseThrow(() -> rejected(assignedDeviceId, id, AuditEvent.Reason.EXECUTION_INELIGIBLE));
        if (transaction.getStatus() != Transaction.Status.VERIFIED
                || transaction.getVerifiedAt() == null
                || !assignedDeviceId.equals(transaction.getDeviceId())
                || transaction.getExpectedGlyphs() == null || transaction.getExpectedGlyphs().isBlank()
                || transaction.getExecutionClaimedAt() != null
                || !"USD".equals(transaction.getCurrency())
                || !policy.allowsAmount(transaction.getAmountMinor())
                || !policy.allowsDestination(transaction.getDestination())) {
            throw rejected(assignedDeviceId, id, AuditEvent.Reason.EXECUTION_INELIGIBLE);
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
