package com.runevault;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction {
 @Id
 @Column(nullable = false, updatable = false, length = 36)
 private String transactionId;
 @Column(nullable = false, updatable = false)
 private long amountMinor;
 @Column(nullable = false, updatable = false, length = 64)
 private String destination;
 @Enumerated(EnumType.STRING)
 @Column(nullable = false, length = 32)
 private Status status;
 @Column(nullable = false, updatable = false)
 private Instant createdAt;
 @Column(nullable = false)
 private Instant updatedAt;

 public enum Status { SIMULATED }

 protected Transaction() {}

 public Transaction(String transactionId, long amountMinor, String destination) {
  this.transactionId = transactionId;
  this.amountMinor = amountMinor;
  this.destination = destination;
  this.status = Status.SIMULATED;
  this.createdAt = Instant.now();
  this.updatedAt = createdAt;
 }

 @PreUpdate
 private void updateTimestamp() { updatedAt = Instant.now(); }

 public String getTransactionId() { return transactionId; }
 public long getAmountMinor() { return amountMinor; }
 public String getDestination() { return destination; }
 public Status getStatus() { return status; }
 public Instant getCreatedAt() { return createdAt; }
 public Instant getUpdatedAt() { return updatedAt; }
}
