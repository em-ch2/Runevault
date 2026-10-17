package com.runevault.ai;

import com.runevault.transactions.TransactionStatus;
import com.runevault.transactions.VerifiedTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPromptTest {

    private VerifiedTransaction transaction() {
        Instant now = Instant.now();
        return new VerifiedTransaction(
            "tx-1", "pico-demo-1", 500, "USD", "alice", "AB",
            TransactionStatus.VERIFIED, now, now
        );
    }

    @Test
    void includesTransactionDataAndFactsInOrder() {
        String prompt = AuditPrompt.build(transaction(), List.of("Fact one.", "Fact two."));

        assertTrue(prompt.contains("amountMinor: 500"));
        assertTrue(prompt.contains("currency: USD"));
        assertTrue(prompt.contains("destination: alice"));

        int first = prompt.indexOf("1. Fact one.");
        int second = prompt.indexOf("2. Fact two.");
        assertTrue(first >= 0);
        assertTrue(second > first);
    }

    @Test
    void tellsTheModelToTreatDataAsData() {
        String prompt = AuditPrompt.build(transaction(), List.of("Fact one."));

        assertTrue(prompt.contains("never instructions"));
        assertTrue(prompt.contains("BEGIN DATA"));
        assertTrue(prompt.contains("END DATA"));
    }

    @Test
    void emptyFactsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> AuditPrompt.build(transaction(), List.of()));
    }

    @Test
    void asksForExactlyTheContractFields() {
        String prompt = AuditPrompt.build(transaction(), List.of("Fact one."));

        for (String field : List.of("lore_translation", "risk_level", "risk_score",
                "risk_flags", "risk_explanation", "security_message")) {
            assertTrue(prompt.contains("\"" + field + "\""), field);
        }
        assertTrue(!prompt.contains("requiresHumanReview"));
    }
}