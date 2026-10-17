package com.runevault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_states")
public class DeviceState {
 @Id
 @Column(length = 64)
 private String deviceId;
 @Column(nullable = false)
 private long lastCounter = -1;
 @Column(nullable = false)
 private int failedAttempts;
 private Instant revokedAt;

 protected DeviceState() {}
 public DeviceState(String deviceId) { this.deviceId = deviceId; }
 public long getLastCounter() { return lastCounter; }
 public int getFailedAttempts() { return failedAttempts; }
 public Instant getRevokedAt() { return revokedAt; }
 public boolean isRevoked() { return revokedAt != null; }
 public void revoke(Instant at) {
  if (revokedAt == null) revokedAt = java.util.Objects.requireNonNull(at)
   .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
 }
 public boolean isLocked(int maxAttempts) { return failedAttempts >= maxAttempts; }
 public void consumeCounter(long counter) { lastCounter = counter; }
 public void wrongGlyphs() { failedAttempts++; }
 public void resetAttempts() { failedAttempts = 0; }
}
