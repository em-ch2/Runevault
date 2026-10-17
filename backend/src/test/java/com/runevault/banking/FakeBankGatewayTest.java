package com.runevault.banking;

import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"dev", "test"})
class FakeBankGatewayTest {
    @Autowired TransactionRepository transactions;
    @Autowired FakeReceiptRepository receipts;
    @Autowired PlatformTransactionManager manager;
    private String pending() {
        String id = UUID.randomUUID().toString();
        transactions.saveAndFlush(Transaction.pending(id,2500,"harbor","slab-01","0-1"));
        return id;
    }
    private FakeBankGateway instance() { return new FakeBankGateway(transactions, receipts, manager); }
    @Test void receiptSurvivesGatewayRecreationAndRejectsDifferentDetails() {
        String id = pending();
        var first = instance().transfer(id,2500,"USD","harbor");
        assertEquals(first, instance().transfer(id,2500,"USD","harbor"));
        assertEquals("SIMULATED", first.status());
        assertEquals(first, receipts.findById(id).orElseThrow().receipt());
        assertThrows(BankRejectedException.class, () -> instance().transfer(id,5000,"USD","harbor"));
        assertThrows(BankRejectedException.class, () -> instance().transfer(id,2500,"USD","other"));
        assertThrows(BankRejectedException.class, () -> instance().transfer(id,2500,"EUR","harbor"));
    }
    @Test void concurrentInstancesReturnSameReceipt() throws Exception {
        String id = pending();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var jobs = new java.util.ArrayList<Future<BankReceipt>>();
            for (int i=0;i<12;i++) jobs.add(pool.submit(() -> instance().transfer(id,2500,"USD","harbor")));
            var first = jobs.getFirst().get(10, TimeUnit.SECONDS);
            for (var job : jobs) assertEquals(first, job.get(10,TimeUnit.SECONDS));
            assertTrue(receipts.existsById(id));
        }
    }
}
