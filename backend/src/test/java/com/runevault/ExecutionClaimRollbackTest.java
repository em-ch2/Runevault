package com.runevault;

import com.runevault.banking.BankGateway;
import com.runevault.transactions.TransactionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:claim-rollback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"dev", "test"})
class ExecutionClaimRollbackTest {
 @Autowired TransactionService service;
 @Autowired TransactionRepository transactions;
 @SpyBean AuditEventRepository auditEvents;
 @MockBean BankGateway bankGateway;

 @Test void failedAuditWriteRollsBackClaimWithoutInvokingBank() {
  var transaction = Transaction.pending("rollback-claim", 50000, "acct-999", "slab-01", "0-2-1-2-1");
  transaction.verifyPhysicalApproval(Instant.now());
  transaction.recordGeminiDecision(AuditEvent.Reason.GEMINI_APPROVED, null, Instant.now());
  transactions.saveAndFlush(transaction);
  doThrow(new IllegalStateException("audit write failed")).when(auditEvents).saveAndFlush(any(AuditEvent.class));
  assertThrows(IllegalStateException.class, () -> service.claimPicoAuthorized(transaction.getTransactionId()));
  var saved = transactions.findById(transaction.getTransactionId()).orElseThrow();
  assertEquals(Transaction.Status.VERIFIED, saved.getStatus());
  assertNull(saved.getExecutionClaimedAt());
  assertEquals(0, auditEvents.count());
  verifyNoInteractions(bankGateway);
 }
}
