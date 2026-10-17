package com.runevault;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.LinkedHashMap;
import java.util.Map;
import com.runevault.transactions.TransactionProcessor;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/device")
public class DeviceApi {
 private final DeviceAuthorizationService service;
 private final TransactionRepository transactions;
 private final DeviceStateRepository devices;
 private final TransactionProcessor processor;
 private final TransactionPolicy policy;
 private final boolean autoExecute;
 public DeviceApi(DeviceAuthorizationService service, TransactionRepository transactions, DeviceStateRepository devices,
   TransactionProcessor processor, TransactionPolicy policy, @Value("${runevault.auto-execute:true}") boolean autoExecute) {
  this.service = service; this.transactions = transactions; this.devices = devices;
  this.processor = processor; this.policy = policy; this.autoExecute = autoExecute;
 }

 public record Authorization(
  @NotNull @Min(1) @Max(1) Integer v,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String device,
  @NotNull @Min(0) Long counter,
  @JsonProperty("tx_id") @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String txId,
  @NotNull @Min(1) @Max(100000000) Long amount,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String dest,
  @NotNull @Pattern(regexp = "[0-2](-[0-2]){0,63}") String glyphs,
  @NotNull @Pattern(regexp = "[0-9a-fA-F]{64}") String sig) {}

 public record Pending(
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String device,
  @NotNull @Min(1) @Max(100000000) Long amount,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String dest,
  @NotNull @Pattern(regexp = "[0-2](-[0-2]){0,63}") String glyphs) {}

 @PostMapping("/transactions")
 public ResponseEntity<Map<String, Object>> pending(@Valid @RequestBody Pending request) {
  return service.createPending(request);
 }

 @GetMapping("/transactions/{id}")
 public Map<String, Object> pendingDetails(@PathVariable String id, @RequestParam String device) {
  if (!id.matches("[A-Za-z0-9_-]{1,36}") || !device.matches("[A-Za-z0-9_-]{1,64}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction or device ID");
  var transaction = transactions.findById(id)
   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
  if (!device.equals(transaction.getDeviceId()))
   throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction assigned to another device");
  if (devices.findById(device).map(DeviceState::isRevoked).orElse(false))
   throw new ResponseStatusException(HttpStatus.LOCKED, "Device revoked");
  if (transaction.getStatus() != Transaction.Status.PENDING)
   throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction already authorized");
  if (transaction.getExpiresAt() == null || !transaction.getExpiresAt().isAfter(Instant.now()))
   throw new ResponseStatusException(HttpStatus.GONE, "Transaction expired");
  return Map.of("tx_id", transaction.getTransactionId(), "amount", transaction.getAmountMinor(),
   "dest", transaction.getDestination(), "device", transaction.getDeviceId(),
   "status", transaction.getStatus().name(), "expires_at", transaction.getExpiresAt().getEpochSecond());
 }

 // Polled by hardware/bridge.py: the next live pending transaction for this device, without its glyphs.
 @GetMapping("/pending")
 public ResponseEntity<Map<String, Object>> nextPending(@RequestParam String device) {
  if (!device.matches("[A-Za-z0-9_-]{1,64}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device ID");
  var state = devices.findById(device).orElse(null);
  if (state != null && state.isRevoked())
   throw new ResponseStatusException(HttpStatus.LOCKED, "Device revoked");
  // A locked device cannot authorize, so do not hand it work to retry forever.
  if (state != null && state.isLocked(policy.maxIncorrectGlyphAttempts()))
   throw new ResponseStatusException(HttpStatus.LOCKED, "Device locked");
  return transactions.findFirstByDeviceIdAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
    device, Transaction.Status.PENDING, Instant.now())
   .map(t -> ResponseEntity.ok(Map.<String, Object>of("tx_id", t.getTransactionId(), "amount", t.getAmountMinor(),
    "dest", t.getDestination(), "device", t.getDeviceId(), "status", t.getStatus().name(),
    "expires_at", t.getExpiresAt().getEpochSecond())))
   .orElseGet(() -> ResponseEntity.noContent().build());
 }

 @PostMapping("/authorize")
 public ResponseEntity<Map<String, Object>> authorize(@Valid @RequestBody Authorization request) {
  var response = service.authorize(request);
  if (!autoExecute || !response.getStatusCode().is2xxSuccessful()) return response;
  // authorize() has committed, so the execution claim sees the VERIFIED row.
  Map<String, Object> body = new LinkedHashMap<>(response.getBody());
  try {
   body.put("execution", processor.process(request.txId()));
  } catch (IllegalStateException e) {
   body.put("execution", Map.of("status", "INELIGIBLE"));
  }
  return ResponseEntity.ok(body);
 }
}
