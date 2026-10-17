package com.runevault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
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
import com.runevault.transactions.TransactionService;
import com.runevault.transactions.VerifiedTransaction;
import com.runevault.ai.AiAuditor;
import com.runevault.ai.AuditResult;


@RestController
@RequestMapping("/api")
public class TransactionApi {
 private final byte[] secret;
 private final BankGateway bankGateway;
 private final TransactionRepository transactions;
 private final TransactionService transactionService;
 private final AiAuditor aiAuditor;
 private final Map<String, Challenge> pending = new ConcurrentHashMap<>();
 public TransactionApi(@Value("${runevault.device-secret}") String secret, @Value("${runevault.mode}") String mode, BankGateway bankGateway, TransactionRepository transactions, TransactionService transactionService, AiAuditor aiAuditor) {
  if (!"local".equals(mode)) {
    throw new IllegalStateException(
      "Production security is not implemented."
    );
  }

  this.secret = secret.getBytes(StandardCharsets.UTF_8);
  this.bankGateway = bankGateway;
  this.transactions = transactions;
  this.transactionService = transactionService;
  this.aiAuditor = aiAuditor;
 }

 public record Intent(@NotNull @Pattern(regexp="pico-demo-1") String deviceId,
  @Min(1) @Max(100000000) long amountMinor,
  @NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String destination,
  @NotNull @Pattern(regexp="[A-D]{1,16}") String glyphs) {}
 public record Challenge(String challengeId, long expiresAt, Intent intent, String signingText) {}
 public record Approval(@NotNull String challengeId, @NotNull @Pattern(regexp="[0-9a-f]{64}") String signature) {}
 @GetMapping("/health") public Map<String,String> health() { return Map.of("status","ok","mode","simulation"); }
 @PostMapping("/challenges") public synchronized Challenge challenge(@Valid @RequestBody Intent intent) {
  long now = Instant.now().getEpochSecond();
  pending.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
  if (pending.size() >= 1000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
  String id = UUID.randomUUID().toString();
  long expiry = now + 120;
  String text = String.join("\n", "runevault-v1", id, intent.deviceId(), Long.toString(intent.amountMinor()), "USD", intent.destination(), intent.glyphs(), Long.toString(expiry));
  Challenge c = new Challenge(id, expiry, intent, text);
  pending.put(id, c);
  return c;
 }

@PostMapping("/approvals")
public synchronized Map<String, Object> approve(
    @Valid @RequestBody Approval approval
) throws Exception {
    Challenge c = pending.get(approval.challengeId());

    if (c == null || c.expiresAt() <= Instant.now().getEpochSecond()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Unknown, expired or consumed challenge"
        );
    }

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));

    byte[] expectedSignature = mac.doFinal(
        c.signingText().getBytes(StandardCharsets.UTF_8)
    );

    byte[] suppliedSignature =
        HexFormat.of().parseHex(approval.signature());

    if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Invalid signature"
        );
    }

    // Preserve the existing database save before consuming the challenge.
    transactions.saveAndFlush(
        new Transaction(
            c.challengeId(),
            c.intent().amountMinor(),
            c.intent().destination()
        )
    );

    if (!pending.remove(c.challengeId(), c)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Already consumed"
        );
    }

    VerifiedTransaction verified =
        transactionService.createVerifiedTransaction(
            c.challengeId(),
            c.intent().deviceId(),
            c.intent().amountMinor(),
            c.intent().destination(),
            c.intent().glyphs()
        );

    AuditResult analysis = aiAuditor.audit(verified);

    BankReceipt receipt = bankGateway.transfer(
        verified.id(),
        verified.amountMinor(),
        verified.currency(),
        verified.destination()
    );

    return Map.of(
        "transactionId", verified.id(),
        "status", receipt.status(),
        "signatureVerified", true,
        "analysis", analysis,
        "receipt", receipt
    );
}

}