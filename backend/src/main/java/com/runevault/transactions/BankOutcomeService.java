package com.runevault.transactions;

import com.runevault.AuditEvent;
import com.runevault.AuditEventRepository;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import com.runevault.banking.BankReceipt;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankOutcomeService {
 private final TransactionRepository transactions;
 private final AuditEventRepository auditEvents;

 public BankOutcomeService(TransactionRepository transactions, AuditEventRepository auditEvents) {
  this.transactions = transactions;
  this.auditEvents = auditEvents;
 }

 // Called after the provider call returns. No network activity runs under this lock.
 @Transactional(propagation = Propagation.REQUIRES_NEW)
 public Transaction record(String id, BankReceipt receipt) {
  Transaction transaction = transactions.findForUpdate(id).orElseThrow();
  transaction.recordExecution(receipt, Instant.now());
  transactions.saveAndFlush(transaction);
  AuditEvent.Outcome outcome = switch (receipt.outcome()) {
   case ACCEPTED_PENDING -> AuditEvent.Outcome.PENDING;
   case COMPLETED -> AuditEvent.Outcome.APPROVED;
   case FAILED -> AuditEvent.Outcome.REJECTED;
   case UNKNOWN -> AuditEvent.Outcome.UNKNOWN;
  };
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.BANK_OUTCOME,
   transaction.getDeviceId(), id, outcome, AuditEvent.Reason.valueOf(receipt.reason().name())));
  return transaction;
 }

 @Transactional(propagation = Propagation.REQUIRES_NEW)
 public boolean reconcile(String id, BankReceipt receipt) {
  Transaction transaction = transactions.findForUpdate(id).orElseThrow();
  boolean changed = transaction.reconcileBankOutcome(receipt, Instant.now());
  if (!changed) return false;
  transactions.saveAndFlush(transaction);
  AuditEvent.Outcome outcome = switch (receipt.outcome()) {
   case ACCEPTED_PENDING -> AuditEvent.Outcome.PENDING;
   case COMPLETED -> AuditEvent.Outcome.APPROVED;
   case FAILED -> AuditEvent.Outcome.REJECTED;
   case UNKNOWN -> AuditEvent.Outcome.UNKNOWN;
  };
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.BANK_OUTCOME,
   transaction.getDeviceId(), id, outcome, AuditEvent.Reason.valueOf(receipt.reason().name())));
  return true;
 }
}
