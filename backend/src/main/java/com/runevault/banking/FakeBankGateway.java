package com.runevault.banking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@ConditionalOnProperty(name = "runevault.mode", havingValue = "local")
public class FakeBankGateway implements BankGateway {
    
    private final ConcurrentMap<String, BankReceipt> receipts = 
        new ConcurrentHashMap<>();

    @Override
    public BankReceipt transfer(
        String transactionId,
        long amountMinor,
        String currency,
        String destination
    ) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID is required.");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        if (!"USD".equals(currency)) {
            throw new IllegalArgumentException("Only USD is supported.");
        }
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Destination is required.");
        }

        return receipts.compute(transactionId, (id, existing) -> {
            if (existing != null) {
                boolean matches = 
                existing.amountMinor() == amountMinor
                && Objects.equals(existing.currency(), currency)
                && Objects.equals(existing.destination(), destination);

                if (!matches) {
                    throw new IllegalArgumentException(
                        "Transaction ID already belongs to a different transfer."
                    );
                }

                return existing;
            } 

            return new BankReceipt(
                "fake-" + id,
                id,
                "fake",
                "SIMULATED",
                amountMinor,
                currency,
                destination
            );
        });
    }
}
