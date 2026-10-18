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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
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

 // OIDC's multi-factor policy URI: Auth0 enforces MFA when a token request carries it (see the step-up Action).
 static final String MFA_ACR = "http://schemas.openid.net/pape/policies/2007/06/multi-factor";

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
   String approverSubject, Instant approvedAt, boolean awaitingApproval, boolean stepUpRequired) {

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
    transaction.getApproverSubject(), transaction.getApprovedAt(),
    approvals.required() && transaction.getStatus() == Transaction.Status.VERIFIED
     && transaction.getGeminiDecisionReason() == AuditEvent.Reason.GEMINI_APPROVED
     && transaction.getExecutionClaimedAt() == null,
    approvals.requiresStepUp(transaction.getAmountMinor()));
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
  return transactions.findByStatusAndGeminiDecisionReasonAndExecutionClaimedAtIsNull(
    Transaction.Status.VERIFIED, AuditEvent.Reason.GEMINI_APPROVED,
    PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("transactionId"))))
   .map(t -> new TransactionView(t, expected.get(t.getTransactionId()), approvals));
 }

 // Maker-checker release. Step-up follows RFC 9470: a token without MFA gets 401 insufficient_user_authentication.
 // The claim re-checks self-approval and step-up under the transaction lock; these early answers are only for the UI.
 @PostMapping("/{id}/approve")
 @PreAuthorize("hasAuthority('PERM_approve:transactions')")
 public ResponseEntity<Map<String, Object>> approve(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
  if (!approvals.required()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Transaction transaction = transactions.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  boolean stepUp = mfa(jwt);
  if (approvals.requiresStepUp(transaction.getAmountMinor()) && !stepUp)
   return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_user_authentication\", "
     + "error_description=\"MFA is required to approve this amount\", acr_values=\"" + MFA_ACR + "\"")
    .body(Map.of("error", "step_up_required"));
  try {
   return ResponseEntity.ok(processor.approve(id, jwt.getSubject(), stepUp));
  } catch (TransactionService.ClaimRejected e) {
   return switch (e.reason()) {
    case SELF_APPROVAL -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "self_approval"));
    case STEP_UP_REQUIRED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "step_up_required"));
    default -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "not_eligible"));
   };
  }
 }

 // Auth0 access tokens carry no amr by default; the step-up Action adds it once MFA has been performed.
 static boolean mfa(Jwt jwt) {
  for (String claim : new String[] {"amr", "https://runevault/amr"})
   if (jwt.getClaims().get(claim) instanceof Collection<?> methods && methods.contains("mfa")) return true;
  return false;
 }
}
