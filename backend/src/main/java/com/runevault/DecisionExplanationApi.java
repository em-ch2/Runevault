package com.runevault;

import com.runevault.lore.DecisionExplanationService;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only "Explain this decision". Kept apart from TransactionProcessor: it only reads the stored record,
 * never changes it, and never reaches the bank. A transaction the caller may not see looks missing.
 */
@RestController
public class DecisionExplanationApi {
 private final TransactionRepository transactions;
 private final DecisionExplanationService explanations;

 public DecisionExplanationApi(TransactionRepository transactions, DecisionExplanationService explanations) {
  this.transactions = transactions;
  this.explanations = explanations;
 }

 @GetMapping("/api/transactions/{id}/explanation")
 @PreAuthorize("hasAnyAuthority('PERM_read:transactions', 'PERM_approve:transactions', 'PERM_read:audit', 'PERM_admin:platform')")
 public DecisionExplanationService.Explanation explain(@PathVariable String id, Authentication authentication) {
  if (!id.matches("[A-Za-z0-9_-]{1,36}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction ID");
  Transaction transaction = transactions.findById(id)
   .filter(t -> visible(t, authentication))
   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  return explanations.explain(transaction);
 }

 // Mirrors the existing views: owners see their own list, CFO reviewers see the approval queue,
 // and auditors and platform admins see company-wide audit history.
 static boolean visible(Transaction t, Authentication authentication) {
  Set<String> granted = new java.util.HashSet<>(authentication.getAuthorities().stream()
   .map(GrantedAuthority::getAuthority).toList());
  if (granted.contains("PERM_read:audit") || granted.contains("PERM_admin:platform")) return true;
  if (granted.contains("PERM_read:transactions") && authentication.getName() != null
    && authentication.getName().equals(t.getRequesterSubject())) return true;
  return granted.contains("PERM_approve:transactions") && t.getStatus() == Transaction.Status.VERIFIED
   && t.getGeminiDecisionReason() == AuditEvent.Reason.GEMINI_APPROVED && t.getExecutionClaimedAt() == null;
 }
}
