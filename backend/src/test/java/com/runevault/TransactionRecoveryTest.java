package com.runevault;

import com.runevault.transactions.TransactionProcessor;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:recovery-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"dev", "test"})
class TransactionRecoveryTest {
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository audits;
 @Autowired TransactionProcessor processor;
 @MockBean com.runevault.lore.LoreAuditService lore;
 @MockBean com.runevault.banking.BankGateway bank;

 @BeforeEach void clear() {
  audits.deleteAll();
  transactions.deleteAll();
  devices.deleteAll();
  reset(lore, bank);
 }

 @Test void committedVerifiedTransactionReusesDecisionAndClaimsOnlyOnce() {
  devices.save(new DeviceState("slab-01"));
  Transaction tx = Transaction.pending("recover-1", 50000, "acct-999", "slab-01", "0-1-2");
  tx.verifyPhysicalApproval(Instant.now());
  tx.recordGeminiDecision(AuditEvent.Reason.GEMINI_APPROVED, null, Instant.now());
  transactions.saveAndFlush(tx);
  when(bank.transfer("recover-1", 50000, "USD", "acct-999"))
   .thenReturn(new com.runevault.banking.BankReceipt("receipt-1", "recover-1", "nessie",
    "pending", 50000, "USD", "acct-999",
    com.runevault.banking.BankReceipt.Outcome.ACCEPTED_PENDING,
    com.runevault.banking.BankReceipt.Reason.ACCEPTED_PENDING, Instant.now(), null));

  assertEquals("BANK_PENDING", processor.process("recover-1").get("status"));
  assertNotNull(transactions.findById("recover-1").orElseThrow().getExecutionClaimedAt());
  assertEquals("INELIGIBLE", processor.process("recover-1").get("status"));
  verifyNoInteractions(lore);
  verify(bank, times(1)).transfer("recover-1", 50000, "USD", "acct-999");
 }

 @Test void alreadyClaimedTransactionIsNeverResubmitted() {
  Transaction tx = Transaction.pending("recover-2", 50000, "acct-999", "slab-01", "0-1-2");
  tx.verifyPhysicalApproval(Instant.now());
  tx.claimForExecution(Instant.now());
  transactions.saveAndFlush(tx);
  assertEquals("INELIGIBLE", processor.process("recover-2").get("status"));
  verifyNoInteractions(bank, lore);
 }
}
