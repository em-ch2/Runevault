package com.runevault.transactions;

import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimRejectionAuditService {
 private final AuditEventRepository auditEvents;
 public ClaimRejectionAuditService(AuditEventRepository auditEvents) { this.auditEvents = auditEvents; }

 @Transactional(propagation = Propagation.REQUIRES_NEW)
 public void record(String deviceId, String transactionId, AuditEvent.Reason reason) {
  record(deviceId, transactionId, reason, null);
 }

 @Transactional(propagation = Propagation.REQUIRES_NEW)
 public void record(String deviceId, String transactionId, AuditEvent.Reason reason, String actorSubject) {
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.EXECUTION_CLAIM, deviceId, transactionId,
   AuditEvent.Outcome.REJECTED, reason, actorSubject));
 }
}
