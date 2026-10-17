package com.runevault;

import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;
import com.runevault.transactions.BankOutcomeService;
import com.runevault.transactions.BankReconciliationService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bank-reconcile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"dev", "test"})
class BankReconciliationTest {
 @Autowired TransactionRepository transactions;
 @Autowired AuditEventRepository audits;
 @Autowired BankOutcomeService outcomes;
 @Autowired BankReconciliationService reconciliation;
 @MockBean BankGateway bank;

 @BeforeEach void clear() { audits.deleteAll(); transactions.deleteAll(); }

 private Transaction claimed(String id) {
  Transaction tx = Transaction.pending(id, 50000, "acct-999", "slab-01", "0-1-2");
  tx.verifyPhysicalApproval(Instant.now());
  tx.claimForExecution(Instant.now());
  return transactions.saveAndFlush(tx);
 }

 @Test void pendingReceiptCanCompleteWithoutAnotherTransferOrDuplicateAudit() {
  String id = "reconcile-pending";
  claimed(id);
  Instant submitted = Instant.now();
  outcomes.record(id, new BankReceipt("receipt-1", id, "nessie", "pending", 50000, "USD",
   "acct-999", BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, submitted, null));
  when(bank.lookup(any())).thenReturn(Optional.of(new BankReceipt("receipt-1", id, "nessie",
   "completed", 50000, "USD", "acct-999", BankReceipt.Outcome.COMPLETED,
   BankReceipt.Reason.CONFIRMED_COMPLETED, submitted, null)));
  assertEquals("EXECUTED", reconciliation.reconcile(id).get("status"));
  assertEquals(Transaction.Status.EXECUTED, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(2, audits.count());
  assertEquals("NOT_ELIGIBLE", reconciliation.reconcile(id).get("status"));
  assertEquals(2, audits.count());
  verify(bank, never()).transfer(any(), anyLong(), any(), any());
 }

 @Test void unknownWithoutReceiptRequiresOperatorReviewAndNeverSubmits() {
  String id = "reconcile-unknown";
  claimed(id);
  outcomes.record(id, new BankReceipt(null, id, "nessie", "NETWORK_UNCERTAIN", 50000, "USD",
   "acct-999", BankReceipt.Outcome.UNKNOWN, BankReceipt.Reason.SUBMISSION_UNCERTAIN, submitted(), null));
  assertEquals("OPERATOR_REVIEW_REQUIRED", reconciliation.reconcile(id).get("status"));
  assertEquals(Transaction.Status.OUTCOME_UNKNOWN, transactions.findById(id).orElseThrow().getStatus());
  verifyNoInteractions(bank);
 }

 @Test void providerLookupFailureKeepsPendingStateAndDoesNotResubmit() {
  String id = "reconcile-offline";
  claimed(id);
  outcomes.record(id, new BankReceipt("receipt-2", id, "nessie", "pending", 50000, "USD",
   "acct-999", BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING,
   Instant.now(), null));
  when(bank.lookup(any())).thenThrow(new IllegalStateException("provider offline"));
  assertEquals("OPERATOR_REVIEW_REQUIRED", reconciliation.reconcile(id).get("status"));
  assertEquals(Transaction.Status.BANK_PENDING, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(1, audits.count());
  verify(bank, never()).transfer(any(), anyLong(), any(), any());
 }

 private Instant submitted() { return Instant.now(); }
}
