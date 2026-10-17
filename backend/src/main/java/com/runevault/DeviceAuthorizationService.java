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
 private final AuditEventRepository auditEvents;
 private final TransactionPolicy policy;
 private final String deviceId;
 private final byte[] key;

 public DeviceAuthorizationService(TransactionRepository transactions, DeviceStateRepository devices,
   AuditEventRepository auditEvents, TransactionPolicy policy,
   @Value("${runevault.pico-device-id}") String deviceId,
   @Value("${runevault.pico-key}") String key) {
  this.transactions = transactions;
  this.devices = devices;
  this.auditEvents = auditEvents;
  this.policy = policy;
  this.deviceId = deviceId;
  this.key = key.getBytes(StandardCharsets.UTF_8);
 }

 private ResponseEntity<Map<String, Object>> error(int code, String reason) {
  return ResponseEntity.status(code).body(Map.of("error", reason));
 }

 private ResponseEntity<Map<String, Object>> rejected(DeviceApi.Authorization request, int code,
   String message, AuditEvent.Reason reason) {
  auditEvents.save(new AuditEvent(request.device(), request.txId(), AuditEvent.Outcome.REJECTED, reason));
  return error(code, message);
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> createPending(DeviceApi.Pending request) {
  return createPending(request, null, false);
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> createPending(DeviceApi.Pending request, String requesterSubject) {
  return createPending(request, requesterSubject, false);
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> createPending(DeviceApi.Pending request, String requesterSubject,
   boolean includeGlyphs) {
  if (key.length == 0) return error(503, "Device key is not configured");
  if (!deviceId.equals(request.device())) return error(404, "Unknown device");
  if (!policy.allowsAmount(request.amount())) {
   auditEvents.save(new AuditEvent(AuditEvent.Type.TRANSACTION_CREATION, request.device(), null,
    AuditEvent.Outcome.REJECTED, AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED));
   return error(400, "Amount exceeds configured limit");
  }
  if (!policy.allowsDestination(request.dest())) {
   auditEvents.save(new AuditEvent(AuditEvent.Type.TRANSACTION_CREATION, request.device(), null,
    AuditEvent.Outcome.REJECTED, AuditEvent.Reason.DESTINATION_NOT_ALLOWED));
   return error(400, "Destination is not allowed");
  }
  var device = devices.findForUpdate(request.device()).orElseGet(() -> devices.saveAndFlush(new DeviceState(request.device())));
  if (device.isRevoked()) {
   auditEvents.save(new AuditEvent(AuditEvent.Type.TRANSACTION_CREATION, request.device(), null,
    AuditEvent.Outcome.REJECTED, AuditEvent.Reason.DEVICE_REVOKED));
   return error(423, "Device revoked");
  }
  if (device.isLocked(policy.maxIncorrectGlyphAttempts())) return error(423, "Device locked");
  var transaction = Transaction.pending(UUID.randomUUID().toString(), request.amount(), request.dest(),
   request.device(), request.glyphs(), requesterSubject);
  transactions.save(transaction);
  Map<String, Object> response = new java.util.LinkedHashMap<>();
  response.put("tx_id", transaction.getTransactionId());
  response.put("status", "PENDING");
  response.put("expires_at", transaction.getExpiresAt().getEpochSecond());
  if (includeGlyphs) response.put("glyphs", transaction.getExpectedGlyphs());
  return ResponseEntity.status(201).body(response);
 }

 public static String signingText(DeviceApi.Authorization request) {
  return "v1|" + request.device() + "|" + request.counter() + "|" + request.txId() + "|"
   + request.amount() + "|" + request.dest() + "|" + request.glyphs();
 }

 @Transactional
 public ResponseEntity<Map<String, Object>> authorize(DeviceApi.Authorization request) {
  if (key.length == 0) return error(503, "Device key is not configured");
  if (!deviceId.equals(request.device())) return rejected(request, 401, "Unknown device", AuditEvent.Reason.UNKNOWN_DEVICE);
  try {
   Mac mac = Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(key, "HmacSHA256"));
   byte[] expected = mac.doFinal(signingText(request).getBytes(StandardCharsets.UTF_8));
   if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(request.sig())))
    return rejected(request, 401, "Invalid signature", AuditEvent.Reason.INVALID_SIGNATURE);
  } catch (GeneralSecurityException e) {
   throw new IllegalStateException("HMAC verification unavailable", e);
  }
  // Lock device first, then transaction: concurrent requests cannot reuse a counter.
  var device = devices.findForUpdate(request.device()).orElse(null);
  if (device == null) return rejected(request, 409, "No pending device transaction", AuditEvent.Reason.NO_PENDING_DEVICE_TRANSACTION);
  if (device.isRevoked()) return rejected(request, 423, "Device revoked", AuditEvent.Reason.DEVICE_REVOKED);
  if (device.isLocked(policy.maxIncorrectGlyphAttempts())) return rejected(request, 423, "Device locked", AuditEvent.Reason.DEVICE_LOCKED);
  if (request.counter() <= device.getLastCounter()) return rejected(request, 409, "Counter replay", AuditEvent.Reason.COUNTER_REPLAY);
  // Consume every authenticated fresh counter, including rejected transaction/glyph attempts.
  device.consumeCounter(request.counter());
  var transaction = transactions.findForUpdate(request.txId()).orElse(null);
  if (transaction == null || transaction.getStatus() != Transaction.Status.PENDING
    || !request.device().equals(transaction.getDeviceId())
    || request.amount() != transaction.getAmountMinor() || !request.dest().equals(transaction.getDestination())) {
   return rejected(request, 409, "Pending transaction mismatch or expired", AuditEvent.Reason.TRANSACTION_MISMATCH);
  }
  if (transaction.getExpiresAt() == null || !transaction.getExpiresAt().isAfter(Instant.now())) {
   return rejected(request, 409, "Pending transaction mismatch or expired", AuditEvent.Reason.TRANSACTION_EXPIRED);
  }
  if (!policy.allowsAmount(transaction.getAmountMinor()))
   return rejected(request, 403, "Amount exceeds configured limit", AuditEvent.Reason.AMOUNT_LIMIT_EXCEEDED);
  if (!policy.allowsDestination(transaction.getDestination()))
   return rejected(request, 403, "Destination is not allowed", AuditEvent.Reason.DESTINATION_NOT_ALLOWED);
  if (!request.glyphs().equals(transaction.getExpectedGlyphs())) {
   device.wrongGlyphs();
   boolean locked = device.isLocked(policy.maxIncorrectGlyphAttempts());
   return rejected(request, locked ? 423 : 403,
    locked ? "Device locked" : "Incorrect glyphs",
    locked ? AuditEvent.Reason.DEVICE_LOCKED : AuditEvent.Reason.INCORRECT_GLYPHS);
  }
  device.resetAttempts();
  transaction.verifyPhysicalApproval(Instant.now());
  auditEvents.save(new AuditEvent(request.device(), request.txId(), AuditEvent.Outcome.APPROVED, AuditEvent.Reason.APPROVED));
  return ResponseEntity.ok(Map.of("transactionId", transaction.getTransactionId(), "status", "VERIFIED",
   "verifiedAt", transaction.getVerifiedAt(), "signatureVerified", true));
 }
}
