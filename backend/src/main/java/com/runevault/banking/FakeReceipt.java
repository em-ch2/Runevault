package com.runevault.banking;

import jakarta.persistence.*;

@Entity
@Table(name = "fake_bank_receipts")
public class FakeReceipt {
    @Id @Column(length = 36) private String transactionId;
    @Column(nullable = false) private long amountMinor;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 64) private String destination;
    @Column(nullable = false, updatable = false) private java.time.Instant createdAt = java.time.Instant.now();
    protected FakeReceipt() {}
    public FakeReceipt(String id, long amount, String currency, String destination) {
        this.transactionId = id; this.amountMinor = amount; this.currency = currency; this.destination = destination;
    }
    public BankReceipt receipt() {
        return new BankReceipt("fake-" + transactionId, transactionId, "fake", "SIMULATED", amountMinor, currency, destination);
    }
}
