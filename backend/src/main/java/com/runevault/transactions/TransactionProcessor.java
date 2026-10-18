package com.runevault.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.ApprovalPolicy;
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
 private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransactionProcessor.class);

 private static String describe(Exception e) {
  if (e instanceof org.springframework.web.client.RestClientResponseException response)
   return "HTTP " + response.getStatusCode().value();
  Throwable root = e;
  while (root.getCause() != null && root.getCause() != root) root = root.getCause();
  return e.getClass().getSimpleName() + (root != e ? " caused by " + root.getClass().getSimpleName() : "");
 }

 private final TransactionRepository transactions;
 private final LoreAuditService lore;
 private final GeminiPolicy policy;
 private final GeminiDecisionService decisions;
 private final TransactionService claims;
 private final ExecutionService execution;
 private final PipelineEvents events;
 private final ApprovalPolicy approvals;

 public TransactionProcessor(TransactionRepository transactions, LoreAuditService lore, GeminiPolicy policy,
   GeminiDecisionService decisions, TransactionService claims, ExecutionService execution, PipelineEvents events,
   ApprovalPolicy approvals) {
  this.transactions = transactions;
  this.lore = lore;
  this.policy = policy;
  this.decisions = decisions;
  this.claims = claims;
  this.execution = execution;
  this.events = events;
  this.approvals = approvals;
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
    analysis = lore.audit(transaction, LoreAuditService.DICTIONARY);
    reason = policy.decide(analysis);
    json = analysis.toString();
   } catch (LoreAuditService.InvalidResponse | com.fasterxml.jackson.core.JsonProcessingException e) {
    log.warn("Gemini returned an invalid assessment for {} ({})", id, e.getClass().getSimpleName());
    reason = AuditEvent.Reason.GEMINI_INVALID_RESPONSE;
   } catch (Exception e) {
    // The cause only (exception type, HTTP status or timeout); never the request, key or model text.
    log.warn("Gemini was unavailable for {}: {}", id, describe(e));
    reason = AuditEvent.Reason.GEMINI_UNAVAILABLE;
   }
   reason = decisions.record(id, reason, json, policy.explain(reason, analysis));
   // The decision has committed; reporting it can never change it.
   events.geminiDecision(id, reason);
  }
  // High risk with maker-checker on: a second approver decides instead of an outright block.
  if (approvals.escalates(reason)) return Map.of("status", "AWAITING_APPROVAL", "reason", reason.name());
  if (reason != AuditEvent.Reason.GEMINI_APPROVED)
   return Map.of("status", "BLOCKED", "reason", reason.name());
  // Maker-checker: a second person releases it later through approve(); nothing is claimed here.
  if (approvals.required()) return Map.of("status", "AWAITING_APPROVAL");
  // The claim rechecks policy, revocation, physical evidence, and the stored AI decision.
  VerifiedTransaction claimed = claims.claimPicoAuthorized(id);
  events.executing(id);
  return execution.executeClaimed(claimed, analysis);
 }

 /** Maker-checker release by a second person; the claim enforces the approver and self-approval rules. */
 public Map<String, Object> approve(String id, String approverSubject) {
  if (TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Approve outside any database transaction");
  VerifiedTransaction claimed = claims.claimApproved(id, approverSubject);
  events.executing(id);
  return execution.executeClaimed(claimed, null);
 }
}
