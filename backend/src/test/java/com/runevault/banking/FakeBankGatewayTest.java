package com.runevault.banking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakeBankGatewayTest {
    private final BankGateway bank = new FakeBankGateway();

    @Test
    void repeatedTransferReturnsOriginalReceipt() {
        BankReceipt first = 
            bank.transfer("tx-1", 2500, "USD", "recipient-1");

        BankReceipt repeated = 
            bank.transfer("tx-1", 2500, "USD", "recipient-1");

        assertEquals(first, repeated);
        assertEquals("SIMULATED", first.status());
    }

    @Test
    void sameIdCannotBeUsedForDifferentTransfers() {
        bank.transfer("tx-1", 2500, "USD", "recipient-1");

        assertThrows(
            IllegalArgumentException.class,
            () -> bank.transfer("tx-1", 5000, "USD", "recipient-1")
        );
    }
}
