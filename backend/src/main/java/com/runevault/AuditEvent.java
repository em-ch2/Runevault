package com.runevault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events", indexes = @Index(name = "idx_audit_events_timestamp", columnList = "timestamp,id"))
public class AuditEvent {
 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 private Long id;
 @Column(nullable = false, updatable = false)
 private Instant timestamp;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, updatable = false, length = 32)
 private Type eventType;
 @Column(updatable = false, length = 64)
 private String deviceId;
 @Column(updatable = false, length = 36)
 private String transactionId;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, updatable = false, length = 16)
 private Outcome outcome;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, updatable = false, length = 40)
 private Reason reason;

 public enum Type { DEVICE_AUTHORIZATION }
 public enum Outcome { APPROVED, REJECTED }
 public enum Reason {
  APPROVED, UNKNOWN_DEVICE, INVALID_SIGNATURE, NO_PENDING_DEVICE_TRANSACTION,
  COUNTER_REPLAY, TRANSACTION_EXPIRED, TRANSACTION_MISMATCH,
  INCORRECT_GLYPHS, DEVICE_LOCKED
 }

 protected AuditEvent() {}

 public AuditEvent(String deviceId, String transactionId, Outcome outcome, Reason reason) {
  timestamp = Instant.now();
  eventType = Type.DEVICE_AUTHORIZATION;
  this.deviceId = deviceId;
  this.transactionId = transactionId;
  this.outcome = outcome;
  this.reason = reason;
 }

 public Long getId() { return id; }
 public Instant getTimestamp() { return timestamp; }
 public Type getEventType() { return eventType; }
 public String getDeviceId() { return deviceId; }
 public String getTransactionId() { return transactionId; }
 public Outcome getOutcome() { return outcome; }
 public Reason getReason() { return reason; }
}
