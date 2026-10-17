package com.runevault.transactions;

import com.runevault.Transaction;
import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;
import com.runevault.events.PipelineEvents;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExecutionService {
 private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

 private final BankGateway bank;
 private final BankOutcomeService outcomes;
 private final PipelineEvents events;

 public ExecutionService(BankGateway bank, BankOutcomeService outcomes, PipelineEvents events) {
  this.bank = bank;
  this.outcomes = outcomes;
  this.events = events;
 }

 // Called only by TransactionProcessor after its durable policy gate and execution claim.
 Map<String, Object> executeClaimed(VerifiedTransaction tx, Object analysis) {
  String id = tx.id();
  BankReceipt receipt;
  try {
   receipt = bank.transfer(tx.id(), tx.amountMinor(), tx.currency(), tx.destination());
  } catch (RuntimeException e) {
   // Conservatively assume submission may have happened. Never resend automatically.
   log.error("Bank transfer outcome unknown for {} ({})", id, e.getClass().getSimpleName());
   receipt = unknown(tx, BankReceipt.Reason.SUBMISSION_UNCERTAIN);
  }
  if (receipt == null || receipt.outcome() == null || receipt.reason() == null
    || receipt.provider() == null || receipt.provider().isBlank()
    || receipt.provider().length() > 32
    || receipt.status() == null || receipt.status().isBlank() || receipt.status().length() > 64
    || receipt.receiptId() != null && receipt.receiptId().length() > 64
    || !tx.id().equals(receipt.transactionId())
    || tx.amountMinor() != receipt.amountMinor()
    || !tx.currency().equals(receipt.currency())
    || !tx.destination().equals(receipt.destination())
    || (receipt.outcome() == BankReceipt.Outcome.COMPLETED
      || receipt.outcome() == BankReceipt.Outcome.ACCEPTED_PENDING)
      && (receipt.receiptId() == null || receipt.receiptId().isBlank())) {
   receipt = unknown(tx, BankReceipt.Reason.MALFORMED_RESPONSE);
  }
  Transaction saved = outcomes.record(id, receipt);
  // The bank outcome has committed; a notification failure cannot change it or resend money.
  events.bankOutcome(saved);
  Map<String, Object> result = new LinkedHashMap<>();
  result.put("status", saved.getStatus().name());
  result.put("receipt", receipt);
  result.put("analysis", analysis);
  return result;
 }

 private BankReceipt unknown(VerifiedTransaction tx, BankReceipt.Reason reason) {
  return new BankReceipt(null, tx.id(), "nessie", "OUTCOME_UNKNOWN", tx.amountMinor(),
   tx.currency(), tx.destination(), BankReceipt.Outcome.UNKNOWN, reason, null, null);
 }

}
