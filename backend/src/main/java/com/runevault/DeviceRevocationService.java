package com.runevault;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceRevocationService {
 private final DeviceStateRepository devices;
 private final AuditEventRepository auditEvents;

 public DeviceRevocationService(DeviceStateRepository devices, AuditEventRepository auditEvents) {
  this.devices = devices;
  this.auditEvents = auditEvents;
 }

 @Transactional
 public Instant revoke(String deviceId, String administratorSubject) {
  if (administratorSubject == null || administratorSubject.isBlank())
   throw new IllegalArgumentException("Administrator subject is required");
  var device = devices.findForUpdate(deviceId)
   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not registered"));
  if (!device.isRevoked()) {
   device.revoke(Instant.now());
   auditEvents.save(new AuditEvent(AuditEvent.Type.DEVICE_REVOCATION, deviceId, null,
    AuditEvent.Outcome.APPROVED, AuditEvent.Reason.REVOKED_BY_ADMIN, administratorSubject));
  }
  return device.getRevokedAt();
 }
}
