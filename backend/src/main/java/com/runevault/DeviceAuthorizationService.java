package com.runevault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceAuthorizationService {
 private final TransactionRepository transactions;
 private final DeviceStateRepository devices;
 private final String deviceId;
 private final byte[] key;

 public DeviceAuthorizationService(TransactionRepository transactions, DeviceStateRepository devices,
   @Value("${runevault.pico-device-id}") String deviceId,
   @Value("${runevault.pico-key}") String key) {
  this.transactions = transactions;
  this.devices = devices;
  this.deviceId = deviceId;
  this.key = key.getBytes(StandardCharsets.UTF_8);
 }

 private ResponseEntity<Map<String, Object>> error(int code, String reason) {
  return ResponseEntity.status(code).body(Map.of("error", reason));
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> createPending(DeviceApi.Pending request) {
  if (key.length == 0) return error(503, "Device key is not configured");
  if (!deviceId.equals(request.device())) return error(404, "Unknown device");
  var device = devices.findForUpdate(request.device()).orElseGet(() -> devices.saveAndFlush(new DeviceState(request.device())));
  if (device.isLocked()) return error(423, "Device locked");
  var transaction = Transaction.pending(UUID.randomUUID().toString(), request.amount(), request.dest(), request.device(), request.glyphs());
  transactions.save(transaction);
  return ResponseEntity.status(201).body(Map.of("tx_id", transaction.getTransactionId(), "status", "PENDING",
   "expires_at", transaction.getExpiresAt().getEpochSecond()));
 }

 public static String signingText(DeviceApi.Authorization request) {
  return "v1|" + request.device() + "|" + request.counter() + "|" + request.txId() + "|"
   + request.amount() + "|" + request.dest() + "|" + request.glyphs();
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> authorize(DeviceApi.Authorization request) {
  if (key.length == 0) return error(503, "Device key is not configured");
  if (!deviceId.equals(request.device())) return error(401, "Unknown device");
  try {
   Mac mac = Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(key, "HmacSHA256"));
   byte[] expected = mac.doFinal(signingText(request).getBytes(StandardCharsets.UTF_8));
   if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(request.sig()))) return error(401, "Invalid signature");
  } catch (GeneralSecurityException e) {
   throw new IllegalStateException("HMAC verification unavailable", e);
  }
  // Lock device first, then transaction: concurrent requests cannot reuse a counter.
  var device = devices.findForUpdate(request.device()).orElse(null);
  if (device == null) return error(409, "No pending device transaction");
  if (device.isLocked()) return error(423, "Device locked");
  if (request.counter() <= device.getLastCounter()) return error(409, "Counter replay");
  // Consume every authenticated fresh counter, including rejected transaction/glyph attempts.
  device.consumeCounter(request.counter());
  var transaction = transactions.findForUpdate(request.txId()).orElse(null);
  if (transaction == null || transaction.getStatus() != Transaction.Status.PENDING
    || !request.device().equals(transaction.getDeviceId())
    || transaction.getExpiresAt() == null || !transaction.getExpiresAt().isAfter(Instant.now())
    || request.amount() != transaction.getAmountMinor() || !request.dest().equals(transaction.getDestination())) {
   return error(409, "Pending transaction mismatch or expired");
  }
  if (!request.glyphs().equals(transaction.getExpectedGlyphs())) {
   device.wrongGlyphs();
   return error(device.isLocked() ? 423 : 403, device.isLocked() ? "Device locked" : "Incorrect glyphs");
  }
  device.resetAttempts();
  transaction.authorizeSimulation();
  return ResponseEntity.ok(Map.of("transactionId", transaction.getTransactionId(), "status", "SIMULATED", "signatureVerified", true));
 }
}
