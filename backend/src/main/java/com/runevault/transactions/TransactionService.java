package com.runevault.transactions;

import java.time.Instant;
import com.runevault.ApprovalPolicy;
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
    private final ApprovalPolicy approvals;
    private final DeviceStateRepository devices;
    private final ClaimRejectionAuditService rejections;

    public TransactionService(TransactionRepository transactions, AuditEventRepository auditEvents,
                              TransactionPolicy policy, ApprovalPolicy approvals, DeviceStateRepository devices,
                              ClaimRejectionAuditService rejections) {
        this.transactions = transactions;
        this.auditEvents = auditEvents;
        this.policy = policy;
        this.approvals = approvals;
        this.devices = devices;
        this.rejections = rejections;
    }

    /** A refused claim; the reason has already been written to the audit log. */
    public static class ClaimRejected extends IllegalStateException {
        private final AuditEvent.Reason reason;
        ClaimRejected(AuditEvent.Reason reason) {
            super("Transaction is not eligible for execution");
            this.reason = reason;
        }
        public AuditEvent.Reason reason() { return reason; }
    }

    private ClaimRejected rejected(String deviceId, String id, AuditEvent.Reason reason) {
        return rejected(deviceId, id, reason, null);
    }

    private ClaimRejected rejected(String deviceId, String id, AuditEvent.Reason reason, String actor) {
        rejections.record(deviceId, id, reason, actor);
        return new ClaimRejected(reason);
    }

    // Call this after the Pico authorization request has committed. The DB claim is
    // durable and serialized by device lock, then transaction lock; never retry execution blindly.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerifiedTransaction claimPicoAuthorized(String id) {
        return claim(id, null);
    }

    // Maker-checker: the approver is checked under the same locks as every other claim condition,
    // so a requester can never release their own transfer, even through a race or a direct call.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerifiedTransaction claimApproved(String id, String approverSubject) {
        return claim(id, approverSubject);
    }

    private VerifiedTransaction claim(String id, String approver) {
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
                || transaction.getGeminiDecisionReason() != AuditEvent.Reason.GEMINI_APPROVED
                || transaction.getGeminiDecisionAt() == null
                || !"USD".equals(transaction.getCurrency())
                || !policy.allowsAmount(transaction.getAmountMinor())
                || !policy.allowsDestination(transaction.getDestination())) {
            throw rejected(assignedDeviceId, id, AuditEvent.Reason.EXECUTION_INELIGIBLE, approver);
        }
        if (approvals.required() || approver != null) {
            if (approver == null || approver.isBlank())
                throw rejected(assignedDeviceId, id, AuditEvent.Reason.APPROVER_REQUIRED);
            if (approver.equals(transaction.getRequesterSubject()))
                throw rejected(assignedDeviceId, id, AuditEvent.Reason.SELF_APPROVAL, approver);
        }
        Instant claimedAt = Instant.now();
        if (approver == null) transaction.claimForExecution(claimedAt);
        else transaction.claimForApprovedExecution(approver, claimedAt);
        transactions.saveAndFlush(transaction);
        auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.EXECUTION_CLAIM,
            transaction.getDeviceId(), transaction.getTransactionId(),
            AuditEvent.Outcome.APPROVED, AuditEvent.Reason.EXECUTION_CLAIMED, approver));
        return new VerifiedTransaction(transaction.getTransactionId(), transaction.getDeviceId(),
            transaction.getAmountMinor(), transaction.getCurrency(), transaction.getDestination(),
            transaction.getExpectedGlyphs(), TransactionStatus.EXECUTING,
            transaction.getCreatedAt(), transaction.getUpdatedAt());
    }

}
