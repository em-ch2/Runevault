package com.runevault.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
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
 private final TransactionRepository transactions;

 public ExecutionService(TransactionService claims, BankGateway bank, LoreAuditService lore,
   TransactionRepository transactions) {
  this.claims = claims;
  this.bank = bank;
  this.lore = lore;
  this.transactions = transactions;
 }

 // Call only after the Pico authorization has committed. Throws IllegalStateException if not eligible.
 public Map<String, Object> execute(String id) {
  VerifiedTransaction tx = claims.claimPicoAuthorized(id);
  Object analysis = audit(tx);
  BankReceipt receipt;
  try {
   receipt = bank.transfer(tx.id(), tx.amountMinor(), tx.currency(), tx.destination());
  } catch (RuntimeException e) {
   // Outcome unknown: leave the transaction EXECUTING and never retry automatically.
   log.error("Bank transfer outcome unknown for {}", id, e);
   return Map.of("status", "EXECUTION_UNKNOWN", "analysis", analysis);
  }
  Transaction saved = transactions.findById(id).orElseThrow();
  saved.recordExecution(receipt.receiptId(), receipt.status());
  transactions.save(saved);
  Map<String, Object> result = new LinkedHashMap<>();
  result.put("status", saved.getStatus().name());
  result.put("receipt", receipt);
  result.put("analysis", analysis);
  return result;
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
