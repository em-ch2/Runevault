package com.runevault.transactions;

import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;
import com.runevault.events.PipelineEvents;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Operator-triggered, single-receipt read. Never submits a transfer. */
@Service
public class BankReconciliationService {
 private final TransactionRepository transactions;
 private final BankGateway bank;
 private final BankOutcomeService outcomes;
 private final PipelineEvents events;

 public BankReconciliationService(TransactionRepository transactions, BankGateway bank,
   BankOutcomeService outcomes, PipelineEvents events) {
  this.transactions = transactions; this.bank = bank; this.outcomes = outcomes; this.events = events;
 }

 public Map<String, Object> reconcile(String id) {
  Transaction tx = transactions.findById(id).orElseThrow();
  if (tx.getStatus() != Transaction.Status.BANK_PENDING
    && tx.getStatus() != Transaction.Status.OUTCOME_UNKNOWN)
   return Map.of("status", "NOT_ELIGIBLE");
  if (tx.getBankReceiptId() == null || tx.getBankReceiptId().isBlank())
   return Map.of("status", "OPERATOR_REVIEW_REQUIRED");
  BankReceipt previous = new BankReceipt(tx.getBankReceiptId(), id, tx.getBankProvider(),
   tx.getBankStatus(), tx.getAmountMinor(), tx.getCurrency(), tx.getDestination(),
   tx.getBankOutcome(), tx.getBankReason(), tx.getBankSubmittedAt(), tx.getNewBalanceMinor());
  java.util.Optional<BankReceipt> observed;
  try { observed = bank.lookup(previous); }
  catch (RuntimeException e) { return Map.of("status", "OPERATOR_REVIEW_REQUIRED"); }
  if (observed.isEmpty()) return Map.of("status", "OPERATOR_REVIEW_REQUIRED");
  boolean changed;
  try { changed = outcomes.reconcile(id, observed.get()); }
  catch (IllegalStateException e) { return Map.of("status", "NOT_ELIGIBLE"); }
  Transaction current = transactions.findById(id).orElseThrow();
  if (changed) events.bankOutcome(current);
  return Map.of("status", current.getStatus().name(), "changed", changed);
 }
}
