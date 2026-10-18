package com.runevault;

import com.runevault.events.PipelineEvents;
import com.runevault.lore.SafeAssessment;
import com.runevault.transactions.TransactionProcessor;
import com.runevault.transactions.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transactions")
public class DashboardTransactionApi {
 private final DeviceAuthorizationService authorization;
 private final TransactionRepository transactions;
 private final ApprovalCodeGenerator glyphGenerator;
 private final PipelineEvents events;
 private final DeviceApi device;
 private final TransactionProcessor processor;
 private final ApprovalPolicy approvals;

 public DashboardTransactionApi(DeviceAuthorizationService authorization, TransactionRepository transactions,
   ApprovalCodeGenerator glyphGenerator, PipelineEvents events, DeviceApi device, TransactionProcessor processor,
   ApprovalPolicy approvals) {
  this.authorization = authorization;
  this.transactions = transactions;
  this.glyphGenerator = glyphGenerator;
  this.events = events;
  this.device = device;
  this.processor = processor;
  this.approvals = approvals;
 }

 public record CreatePending(
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String device,
  @NotNull @Min(1) @Max(100000000) Long amount,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String dest) {}

 public record TransactionView(String transactionId, long amountMinor, String destination,
   Transaction.Status status, Instant createdAt, Instant expiresAt, Instant verifiedAt, String requesterSubject,
   String bankReceiptId, String bankStatus, String bankProvider,
   com.runevault.banking.BankReceipt.Outcome bankOutcome,
   com.runevault.banking.BankReceipt.Reason bankReason,
   Instant bankSubmittedAt, Instant bankOutcomeAt, Long newBalanceMinor, Long expectedBalanceMinor,
   String lore, Double translationConfidence, Double riskScore, List<String> riskFlags,
   String riskExplanation, String securityMessage,
   AuditEvent.Reason geminiDecisionReason, String geminiDecisionExplanation, Instant geminiDecisionAt,
   String approverSubject, Instant approvedAt,
   // Historical only: set by approvals made under the retired step-up MFA rule, null for newer approvals.
   Boolean approvalStepUp,
   boolean awaitingApproval) {

  TransactionView(Transaction transaction, Long expectedBalanceMinor, ApprovalPolicy approvals) {
   this(transaction, SafeAssessment.of(transaction), expectedBalanceMinor, approvals);
  }

  // Gemini text goes through SafeAssessment: plain, capped, and never the approval glyphs.
  private TransactionView(Transaction transaction, SafeAssessment audit, Long expectedBalanceMinor, ApprovalPolicy approvals) {
   this(transaction.getTransactionId(), transaction.getAmountMinor(), transaction.getDestination(),
    transaction.getStatus(), transaction.getCreatedAt(), transaction.getExpiresAt(),
    transaction.getVerifiedAt(), transaction.getRequesterSubject(),
    transaction.getBankReceiptId(), transaction.getBankStatus(), transaction.getBankProvider(),
    transaction.getBankOutcome(), transaction.getBankReason(),
    transaction.getBankSubmittedAt(), transaction.getBankOutcomeAt(), transaction.getNewBalanceMinor(),
    expectedBalanceMinor,
    audit.lore(), audit.translationConfidence(), audit.riskScore(), audit.riskFlags(),
    audit.riskExplanation(), audit.securityMessage(),
    transaction.getGeminiDecisionReason(), transaction.getGeminiDecisionExplanation(), transaction.getGeminiDecisionAt(),
    transaction.getApproverSubject(), transaction.getApprovedAt(), transaction.getApprovalStepUp(),
    approvals.required() && transaction.getStatus() == Transaction.Status.VERIFIED
     && approvals.releasable(transaction.getGeminiDecisionReason())
     && transaction.getExecutionClaimedAt() == null);
  }
 }

 @PostMapping
 @PreAuthorize("hasAuthority('PERM_create:transactions')")
 public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePending request,
   @AuthenticationPrincipal Jwt jwt) {
  String glyphs = glyphGenerator.generate();
  var response = authorization.createPending(
   new DeviceApi.Pending(request.device(), request.amount(), request.dest(), glyphs), jwt.getSubject(), true);
  events.createdIfSuccessful(response);
  return response;
 }

 // The dashboard relays the Pico's signed approval over Web Serial, so the bridge token never reaches the browser.
 // Only the transfer's creator may relay it; the signature, counter, and glyph checks are the bridge path's own.
 @PostMapping("/{id}/authorize")
 @PreAuthorize("hasAuthority('PERM_create:transactions')")
 public ResponseEntity<Map<String, Object>> authorize(@PathVariable String id,
   @Valid @RequestBody DeviceApi.Authorization request, @AuthenticationPrincipal Jwt jwt) {
  boolean owned = id.equals(request.txId()) && transactions.findById(id)
   .map(t -> jwt.getSubject().equals(t.getRequesterSubject())).orElse(false);
  if (!owned) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return device.authorize(request);
 }

 @GetMapping
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public Page<TransactionView> list(@RequestParam(defaultValue = "0") int page,
   @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
  if (page < 0 || size < 1 || size > 100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be 1-100");
  Map<String, Long> expected = expectedBalances();
  return transactions.findByRequesterSubject(jwt.getSubject(), PageRequest.of(page, size,
    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("transactionId"))))
   .map(t -> new TransactionView(t, expected.get(t.getTransactionId()), approvals));
 }

 // The Nessie sandbox marks transfers completed but never deducts them, so the balance it reports stays
 // flat. Each executed transfer's expected balance is the balance reported at its settlement minus every
 // transfer executed up to and including it, across all users of the shared source account.
 private Map<String, Long> expectedBalances() {
  Map<String, Long> expected = new HashMap<>();
  long settled = 0;
  for (Transaction t : transactions.findByStatusOrderByBankOutcomeAtAscTransactionIdAsc(Transaction.Status.EXECUTED)) {
   settled += t.getAmountMinor();
   if (t.getNewBalanceMinor() != null) expected.put(t.getTransactionId(), t.getNewBalanceMinor() - settled);
  }
  return expected;
 }

 // Transfers waiting for a checker, including the caller's own so the dashboard can explain why it is locked.
 @GetMapping("/approvals")
 @PreAuthorize("hasAuthority('PERM_approve:transactions')")
 public Page<TransactionView> pendingApprovals(@RequestParam(defaultValue = "0") int page,
   @RequestParam(defaultValue = "20") int size) {
  if (page < 0 || size < 1 || size > 100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be 1-100");
  Map<String, Long> expected = expectedBalances();
  // Gemini-approved transfers, plus high-risk ones escalated to a second approver instead of being blocked.
  var releasable = approvals.required()
   ? List.of(AuditEvent.Reason.GEMINI_APPROVED, AuditEvent.Reason.GEMINI_HIGH_RISK) : List.of(AuditEvent.Reason.GEMINI_APPROVED);
  return transactions.findByStatusAndGeminiDecisionReasonInAndExecutionClaimedAtIsNull(
    Transaction.Status.VERIFIED, releasable,
    PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("transactionId"))))
   .map(t -> new TransactionView(t, expected.get(t.getTransactionId()), approvals));
 }

 // Maker-checker release with the approver's normal access token at every amount. The claim re-checks
 // the approver and self-approval under the transaction lock.
 @PostMapping("/{id}/approve")
 @PreAuthorize("hasAuthority('PERM_approve:transactions')")
 public ResponseEntity<Map<String, Object>> approve(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
  if (!approvals.required()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  if (transactions.findById(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  try {
   return ResponseEntity.ok(processor.approve(id, jwt.getSubject()));
  } catch (TransactionService.ClaimRejected e) {
   return e.reason() == AuditEvent.Reason.SELF_APPROVAL
    ? ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "self_approval"))
    : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "not_eligible"));
  }
 }
}
