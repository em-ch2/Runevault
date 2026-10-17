package com.runevault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 public Map<String,String> status() { return Map.of("mode","simulation","storage","in-memory"); }
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
   @RequestParam(defaultValue = "20") int size) {
  if (page < 0 || size < 1 || size > 100)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be 1–100");
  return auditEvents.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"))));
 }
}
