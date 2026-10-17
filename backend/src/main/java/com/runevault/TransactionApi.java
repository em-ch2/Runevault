package com.runevault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;


@RestController
@Profile("simulator & dev & !auth")
@RequestMapping("/api")
public class TransactionApi {
 private final byte[] secret;
 private final BankGateway bankGateway;
 private final TransactionRepository transactions;
 private final TransactionPolicy policy;
 private final AuditEventRepository auditEvents;
 private final Map<String, Challenge> pending = new ConcurrentHashMap<>();
 public TransactionApi(@Value("${runevault.device-secret}") String secret, BankGateway bankGateway, TransactionRepository transactions, TransactionPolicy policy, AuditEventRepository auditEvents) {
  this.secret = secret.getBytes(StandardCharsets.UTF_8);
  this.bankGateway = bankGateway;
  this.transactions = transactions;
  this.policy = policy;
  this.auditEvents = auditEvents;
 }

 public record Intent(@NotNull @Pattern(regexp="pico-demo-1") String deviceId,
  @Min(1) @Max(100000000) long amountMinor,
  @NotNull @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String destination,
  @NotNull @Pattern(regexp="[0-2](-[0-2]){0,63}") String glyphs) {}
 public record Challenge(String challengeId, long expiresAt, Intent intent, String signingText, String mode) {}
 public record Approval(@NotNull String challengeId, @NotNull @Pattern(regexp="[0-9a-f]{64}") String signature) {}
 private void requirePolicy(long amount, String destination, String device, String transactionId, AuditEvent.Type type) {
  AuditEvent.Reason reason = !policy.allowsAmount(amount) ? AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED
   : !policy.allowsDestination(destination) ? AuditEvent.Reason.DESTINATION_NOT_ALLOWED : null;
  if (reason != null) {
   auditEvents.save(new AuditEvent(type, device, transactionId, AuditEvent.Outcome.REJECTED, reason));
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
    reason == AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED ? "Amount exceeds configured limit" : "Destination is not allowed");
  }
 }
 @PostMapping("/challenges") public synchronized Challenge challenge(@Valid @RequestBody Intent intent) {
  requirePolicy(intent.amountMinor(), intent.destination(), intent.deviceId(), null, AuditEvent.Type.TRANSACTION_CREATION);
  long now = Instant.now().getEpochSecond();
  pending.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
  if (pending.size() >= 1000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
  String id = UUID.randomUUID().toString();
  long expiry = now + 120;
  String text = String.join("\n", "runevault-v1", id, intent.deviceId(), Long.toString(intent.amountMinor()), "USD", intent.destination(), intent.glyphs(), Long.toString(expiry));
  Challenge c = new Challenge(id, expiry, intent, text, "simulator");
  pending.put(id, c);
  return c;
 }
 @PostMapping("/approvals") public synchronized Map<String,Object> approve(@Valid @RequestBody Approval approval) throws Exception {
  Challenge c = pending.get(approval.challengeId());
  if (c == null || c.expiresAt() <= Instant.now().getEpochSecond()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Unknown, expired or consumed challenge");
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(secret,"HmacSHA256"));
  if (!MessageDigest.isEqual(mac.doFinal(c.signingText().getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(approval.signature()))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid signature");
  requirePolicy(c.intent().amountMinor(), c.intent().destination(), c.intent().deviceId(), c.challengeId(), AuditEvent.Type.DEVICE_AUTHORIZATION);
  // Save before consuming the challenge or transferring, so a failed write can be retried.
  Transaction simulated = transactions.saveAndFlush(new Transaction(c.challengeId(), c.intent().amountMinor(), c.intent().destination()));
  if (!pending.remove(c.challengeId(), c)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Already consumed");
  BankReceipt receipt = bankGateway.transfer(simulated.getTransactionId(), simulated.getAmountMinor(), simulated.getCurrency(), simulated.getDestination());

  return Map.of(
    "transactionId", c.challengeId(),
    "status", receipt.status(),
    "mode", "simulator",
    "signatureVerified", true,
    "analysis", Map.of(
      "lore", "The stone records a passage across the sea.",
      "riskLevel", "LOW",
      "source", "mock"
    ),
    "receipt", receipt
  );
 }
}
