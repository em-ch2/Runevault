package com.runevault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
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

 public DashboardTransactionApi(DeviceAuthorizationService authorization, TransactionRepository transactions) {
  this.authorization = authorization;
  this.transactions = transactions;
 }

 public record TransactionView(String transactionId, long amountMinor, String destination,
   Transaction.Status status, Instant createdAt, Instant verifiedAt, String requesterSubject,
   String bankReceiptId, String bankStatus, String bankProvider,
   com.runevault.banking.BankReceipt.Outcome bankOutcome,
   com.runevault.banking.BankReceipt.Reason bankReason,
   Instant bankSubmittedAt, Instant bankOutcomeAt,
   String lore, Double translationConfidence, Integer riskScore, List<String> riskFlags,
   String riskExplanation, String securityMessage,
   AuditEvent.Reason geminiDecisionReason, Instant geminiDecisionAt) {
  private static final ObjectMapper JSON = new ObjectMapper();

  TransactionView(Transaction transaction) {
   this(transaction, audit(transaction));
  }

  private TransactionView(Transaction transaction, JsonNode audit) {
   this(transaction.getTransactionId(), transaction.getAmountMinor(), transaction.getDestination(),
    transaction.getStatus(), transaction.getCreatedAt(), transaction.getVerifiedAt(), transaction.getRequesterSubject(),
    transaction.getBankReceiptId(), transaction.getBankStatus(), transaction.getBankProvider(),
    transaction.getBankOutcome(), transaction.getBankReason(),
    transaction.getBankSubmittedAt(), transaction.getBankOutcomeAt(),
    audit.path("lore_translation").asText(null),
    audit.path("translation_confidence").isNumber() ? audit.path("translation_confidence").asDouble() : null,
    audit.path("risk_score").isNumber() ? audit.path("risk_score").asInt() : null,
    audit.path("risk_flags").isArray()
     ? StreamSupport.stream(audit.path("risk_flags").spliterator(), false).map(JsonNode::asText).toList() : null,
    audit.path("risk_explanation").asText(null),
    audit.path("security_message").asText(null),
    transaction.getGeminiDecisionReason(), transaction.getGeminiDecisionAt());
  }

  // Rows audited with the older lore-risk-v1 shape, or not at all, show no Gemini fields.
  private static JsonNode audit(Transaction transaction) {
   if (transaction.getAuditJson() == null) return MissingNode.getInstance();
   try {
    return JSON.readTree(transaction.getAuditJson());
   } catch (Exception e) {
    return MissingNode.getInstance();
   }
  }
 }

 @PostMapping
 @PreAuthorize("hasAuthority('PERM_create:transactions')")
 public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody DeviceApi.Pending request,
   @AuthenticationPrincipal Jwt jwt) {
  return authorization.createPending(request, jwt.getSubject());
 }

 @GetMapping
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public Page<TransactionView> list(@RequestParam(defaultValue = "0") int page,
   @RequestParam(defaultValue = "20") int size) {
  if (page < 0 || size < 1 || size > 100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be 1-100");
  return transactions.findAll(PageRequest.of(page, size,
    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("transactionId"))))
   .map(TransactionView::new);
 }
}
