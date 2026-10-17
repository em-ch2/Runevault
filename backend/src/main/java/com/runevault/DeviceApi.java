package com.runevault;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
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
 public DeviceApi(DeviceAuthorizationService service, TransactionRepository transactions, DeviceStateRepository devices) {
  this.service = service; this.transactions = transactions; this.devices = devices;
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

 @PostMapping("/authorize")
 public ResponseEntity<Map<String, Object>> authorize(@Valid @RequestBody Authorization request) {
  return service.authorize(request);
 }
}
