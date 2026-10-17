package com.runevault;

import com.runevault.banking.BankReceipt;
import com.runevault.transactions.BankOutcomeService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.*;

class BankOutcomePersistenceTest {
 @TempDir Path directory;

 @Test void allBankOutcomesAndAuditEventsSurviveRestart() {
  String url = "jdbc:h2:file:" + directory.resolve("bank-outcomes").toAbsolutePath().toString().replace('\\', '/');
  String[] arguments = {"--server.port=0", "--spring.datasource.url=" + url,
   "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=never",
   "--runevault.bridge-token=test-bridge-token-with-enough-characters"};
  BankReceipt.Outcome[] outcomes = BankReceipt.Outcome.values();
  String[] ids = new String[outcomes.length];
  try (var first = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test").run(arguments)) {
   var transactions = first.getBean(TransactionRepository.class);
   var service = first.getBean(BankOutcomeService.class);
   for (int i = 0; i < outcomes.length; i++) {
    ids[i] = UUID.randomUUID().toString();
    String id = ids[i];
    var tx = Transaction.pending(ids[i], 50000, "acct-999", "slab-01", "0-1-2");
    tx.verifyPhysicalApproval(Instant.now());
    tx.claimForExecution(Instant.now());
    transactions.saveAndFlush(tx);
    BankReceipt.Outcome outcome = outcomes[i];
    BankReceipt.Reason reason = switch (outcome) {
     case ACCEPTED_PENDING -> BankReceipt.Reason.ACCEPTED_PENDING;
     case COMPLETED -> BankReceipt.Reason.CONFIRMED_COMPLETED;
     case FAILED -> BankReceipt.Reason.DEFINITE_REJECTION;
     case UNKNOWN -> BankReceipt.Reason.SUBMISSION_UNCERTAIN;
    };
    String receiptId = outcome == BankReceipt.Outcome.UNKNOWN || outcome == BankReceipt.Outcome.FAILED
     ? null : "receipt-" + i;
    service.record(ids[i], new BankReceipt(receiptId, ids[i], "nessie", outcome.name(),
     50000, "USD", "acct-999", outcome, reason, Instant.now(), null));
    assertThrows(IllegalStateException.class, () -> service.record(id,
     new BankReceipt(receiptId, id, "nessie", outcome.name(), 50000, "USD", "acct-999",
      outcome, reason, Instant.now(), null)));
   }
  }
  try (var second = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test").run(arguments)) {
   var transactions = second.getBean(TransactionRepository.class);
   Transaction.Status[] statuses = {Transaction.Status.BANK_PENDING, Transaction.Status.EXECUTED,
    Transaction.Status.FAILED, Transaction.Status.OUTCOME_UNKNOWN};
   for (int i = 0; i < ids.length; i++) {
    var saved = transactions.findById(ids[i]).orElseThrow();
    assertEquals(statuses[i], saved.getStatus());
    assertEquals(outcomes[i], saved.getBankOutcome());
    assertEquals("nessie", saved.getBankProvider());
    assertNotNull(saved.getBankOutcomeAt());
    assertNotNull(saved.getBankSubmittedAt());
    assertNotNull(saved.getExecutionClaimedAt());
   }
   var events = second.getBean(AuditEventRepository.class).findAll();
   assertEquals(4, events.size());
   assertTrue(events.stream().allMatch(event -> event.getEventType() == AuditEvent.Type.BANK_OUTCOME));
  }
 }
}
