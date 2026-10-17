package com.runevault;

import jakarta.persistence.*;

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

 protected DeviceState() {}
 public DeviceState(String deviceId) { this.deviceId = deviceId; }
 public long getLastCounter() { return lastCounter; }
 public int getFailedAttempts() { return failedAttempts; }
 public boolean isLocked() { return failedAttempts >= 5; }
 public void consumeCounter(long counter) { lastCounter = counter; }
 public void wrongGlyphs() { failedAttempts++; }
 public void resetAttempts() { failedAttempts = 0; }
}
