package com.runevault.transactions;

import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GeminiDecisionService {
 private final TransactionRepository transactions;
 private final AuditEventRepository auditEvents;

 public GeminiDecisionService(TransactionRepository transactions, AuditEventRepository auditEvents) {
  this.transactions = transactions;
  this.auditEvents = auditEvents;
 }

 @Transactional(propagation = Propagation.REQUIRES_NEW)
 public AuditEvent.Reason record(String id, AuditEvent.Reason reason, String json) {
  Transaction transaction = transactions.findForUpdate(id).orElseThrow();
  if (transaction.getGeminiDecisionReason() != null) return transaction.getGeminiDecisionReason();
  transaction.recordGeminiDecision(reason, json, Instant.now());
  transactions.saveAndFlush(transaction);
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.GEMINI_DECISION,
   transaction.getDeviceId(), id,
   reason == AuditEvent.Reason.GEMINI_APPROVED ? AuditEvent.Outcome.APPROVED : AuditEvent.Outcome.REJECTED,
   reason));
  return reason;
 }
}
