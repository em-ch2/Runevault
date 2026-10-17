package com.runevault.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.Transaction;
import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;
import com.runevault.lore.LoreAuditService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExecutionService {
 private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

 private final TransactionService claims;
 private final BankGateway bank;
 private final LoreAuditService lore;
 private final com.runevault.TransactionRepository transactions;
 private final BankOutcomeService outcomes;

 public ExecutionService(TransactionService claims, BankGateway bank, LoreAuditService lore,
   com.runevault.TransactionRepository transactions, BankOutcomeService outcomes) {
  this.claims = claims;
  this.bank = bank;
  this.lore = lore;
  this.transactions = transactions;
  this.outcomes = outcomes;
 }

 // Call only after the Pico authorization has committed. Throws IllegalStateException if not eligible.
 public Map<String, Object> execute(String id) {
  VerifiedTransaction tx = claims.claimPicoAuthorized(id);
  Object analysis = audit(tx);
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
  Map<String, Object> result = new LinkedHashMap<>();
  result.put("status", saved.getStatus().name());
  result.put("receipt", receipt);
  result.put("analysis", analysis);
  return result;
 }

 private BankReceipt unknown(VerifiedTransaction tx, BankReceipt.Reason reason) {
  return new BankReceipt(null, tx.id(), "nessie", "OUTCOME_UNKNOWN", tx.amountMinor(),
   tx.currency(), tx.destination(), BankReceipt.Outcome.UNKNOWN, reason, null);
 }

 // Gemini is advisory only: a failure here never blocks or changes the transfer.
 private Object audit(VerifiedTransaction tx) {
  try {
   JsonNode result = lore.audit(tx.glyphs(), LoreAuditService.DICTIONARY, tx.amountMinor(), tx.destination());
   transactions.storeAudit(tx.id(), result.toString(), "gemini", LoreAuditService.PROMPT_VERSION, Instant.now());
   return result;
  } catch (Exception e) {
   log.warn("Gemini audit failed for {}", tx.id(), e);
   return Map.of("error", "Lore unavailable");
  }
 }
}
