package com.runevault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/admin")
public class PlatformApi {
 private final AuditEventRepository auditEvents;
 private final DeviceRevocationService revocations;
 public PlatformApi(AuditEventRepository auditEvents, DeviceRevocationService revocations) {
  this.auditEvents = auditEvents;
  this.revocations = revocations;
 }
 @GetMapping("/status")
 @PreAuthorize("hasAuthority('PERM_admin:platform')")
 public Map<String,String> status() { return Map.of("mode","auth","storage","postgresql"); }
 @PostMapping("/devices/{deviceId}/revoke")
 @PreAuthorize("hasAuthority('PERM_admin:platform')")
 public Map<String, Object> revoke(@PathVariable String deviceId, @AuthenticationPrincipal Jwt jwt) {
  if (!deviceId.matches("[A-Za-z0-9_-]{1,64}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device ID");
  return Map.of("deviceId", deviceId, "revokedAt", revocations.revoke(deviceId, jwt.getSubject()),
   "revoked", true);
 }
 @GetMapping("/audit-events")
 @PreAuthorize("hasAuthority('PERM_admin:platform')")
 public Page<AuditEvent> auditEvents(@RequestParam(defaultValue = "0") int page,
   @RequestParam(defaultValue = "20") int size,
   @RequestParam(required = false) String deviceId,
   @RequestParam(required = false) String transactionId,
   @RequestParam(required = false) String eventType,
   @RequestParam(required = false) String outcome,
   @RequestParam(required = false) String reason,
   @RequestParam(name = "from", required = false) String from,
   @RequestParam(name = "to", required = false) String to) {
  if (page < 0 || size < 1 || size > 100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be 1–100");
  AuditEvent.Type typeFilter = parseEnum(AuditEvent.Type.class, eventType);
  AuditEvent.Outcome outcomeFilter = parseEnum(AuditEvent.Outcome.class, outcome);
  AuditEvent.Reason reasonFilter = parseEnum(AuditEvent.Reason.class, reason);
  Instant fromFilter = parseInstant(from);
  Instant toFilter = parseInstant(to);
  if (fromFilter != null && toFilter != null && fromFilter.isAfter(toFilter))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to");
  Specification<AuditEvent> filters = (root, query, cb) -> {
   var predicates = new ArrayList<Predicate>();
   if (deviceId != null) predicates.add(cb.equal(root.get("deviceId"), deviceId));
   if (transactionId != null) predicates.add(cb.equal(root.get("transactionId"), transactionId));
   if (typeFilter != null) predicates.add(cb.equal(root.get("eventType"), typeFilter));
   if (outcomeFilter != null) predicates.add(cb.equal(root.get("outcome"), outcomeFilter));
   if (reasonFilter != null) predicates.add(cb.equal(root.get("reason"), reasonFilter));
   if (fromFilter != null) predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), fromFilter));
   if (toFilter != null) predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), toFilter));
   return cb.and(predicates.toArray(Predicate[]::new));
  };
  return auditEvents.findAll(filters, PageRequest.of(page, size,
   Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"))));
 }
 private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
  if (value == null) return null;
  try { return Enum.valueOf(type, value); }
  catch (IllegalArgumentException ex) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + type.getSimpleName());
  }
 }
 private static Instant parseInstant(String value) {
  if (value == null) return null;
  try { return OffsetDateTime.parse(value).toInstant(); }
  catch (DateTimeParseException ex) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timestamp must be ISO-8601 with timezone");
  }
 }
}
