package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Instant;
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

 public DashboardTransactionApi(DeviceAuthorizationService authorization, TransactionRepository transactions) {
  this.authorization = authorization;
  this.transactions = transactions;
 }

 public record TransactionView(String transactionId, long amountMinor, String destination,
   Transaction.Status status, Instant createdAt, Instant verifiedAt, String requesterSubject,
   String bankReceiptId, String bankStatus, String bankProvider,
   com.runevault.banking.BankReceipt.Outcome bankOutcome,
   com.runevault.banking.BankReceipt.Reason bankReason,
   Instant bankSubmittedAt, Instant bankOutcomeAt, String lore, String riskLevel) {
  private static final ObjectMapper JSON = new ObjectMapper();

  TransactionView(Transaction transaction) {
   this(transaction.getTransactionId(), transaction.getAmountMinor(), transaction.getDestination(),
    transaction.getStatus(), transaction.getCreatedAt(), transaction.getVerifiedAt(), transaction.getRequesterSubject(),
    transaction.getBankReceiptId(), transaction.getBankStatus(), transaction.getBankProvider(),
    transaction.getBankOutcome(), transaction.getBankReason(),
    transaction.getBankSubmittedAt(), transaction.getBankOutcomeAt(),
    audit(transaction, "lore", "text"), audit(transaction, "risk", "level"));
  }

  private static String audit(Transaction transaction, String section, String field) {
   if (transaction.getAuditJson() == null) return null;
   try {
    return JSON.readTree(transaction.getAuditJson()).path(section).path(field).asText(null);
   } catch (Exception e) {
    return null;
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
