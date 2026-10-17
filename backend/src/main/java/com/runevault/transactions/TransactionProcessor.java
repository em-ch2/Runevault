package com.runevault.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.AuditEvent;
import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import com.runevault.events.PipelineEvents;
import com.runevault.lore.LoreAuditService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One live orchestration path, invoked only after physical authorization commits. */
@Service
public class TransactionProcessor {
 private final TransactionRepository transactions;
 private final LoreAuditService lore;
 private final GeminiPolicy policy;
 private final GeminiDecisionService decisions;
 private final TransactionService claims;
 private final ExecutionService execution;
 private final PipelineEvents events;

 public TransactionProcessor(TransactionRepository transactions, LoreAuditService lore, GeminiPolicy policy,
   GeminiDecisionService decisions, TransactionService claims, ExecutionService execution, PipelineEvents events) {
  this.transactions = transactions;
  this.lore = lore;
  this.policy = policy;
  this.decisions = decisions;
  this.claims = claims;
  this.execution = execution;
  this.events = events;
 }

 public Map<String, Object> process(String id) {
  if (TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Process only after physical verification commits");
  Transaction transaction = transactions.findById(id).orElseThrow();
  if (transaction.getStatus() != Transaction.Status.VERIFIED || transaction.getVerifiedAt() == null)
   return Map.of("status", "INELIGIBLE");
  AuditEvent.Reason reason = transaction.getGeminiDecisionReason();
  JsonNode analysis = null;
  if (reason == null) {
   String json = null;
   try {
    analysis = lore.audit(transaction.getExpectedGlyphs(), LoreAuditService.DICTIONARY,
     transaction.getAmountMinor(), transaction.getDestination());
    reason = policy.decide(analysis);
    json = analysis.toString();
   } catch (LoreAuditService.InvalidResponse | com.fasterxml.jackson.core.JsonProcessingException e) {
    reason = AuditEvent.Reason.GEMINI_INVALID_RESPONSE;
   } catch (Exception e) {
    reason = AuditEvent.Reason.GEMINI_UNAVAILABLE;
   }
   reason = decisions.record(id, reason, json, policy.explain(reason, analysis));
   // The decision has committed; reporting it can never change it.
   events.geminiDecision(id, reason);
  }
  if (reason != AuditEvent.Reason.GEMINI_APPROVED)
   return Map.of("status", "BLOCKED", "reason", reason.name());
  // The claim rechecks policy, revocation, physical evidence, and the stored AI decision.
  VerifiedTransaction claimed = claims.claimPicoAuthorized(id);
  events.executing(id);
  return execution.executeClaimed(claimed, analysis);
 }
}
