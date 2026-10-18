package com.runevault.transactions;

import com.runevault.Transaction;
import com.runevault.TransactionRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads Nessie receipts for recent BANK_PENDING transfers so they reach EXECUTED (with the settled
 * balance) or FAILED without an operator. Read-only: it goes through the same receipt lookup as the admin
 * reconcile endpoint and never submits or resends a transfer. OUTCOME_UNKNOWN stays operator-only.
 */
@Component
@ConditionalOnProperty(name = "runevault.bank-settlement.enabled", havingValue = "true", matchIfMissing = true)
public class BankSettlementPoller {
 private static final Logger log = LoggerFactory.getLogger(BankSettlementPoller.class);
 private final TransactionRepository transactions;
 private final BankReconciliationService reconciliation;
 private final Duration window;

 public BankSettlementPoller(TransactionRepository transactions, BankReconciliationService reconciliation,
   @Value("${runevault.bank-settlement.window-hours:24}") long windowHours) {
  this.transactions = transactions;
  this.reconciliation = reconciliation;
  this.window = Duration.ofHours(windowHours);
 }

 @Scheduled(initialDelayString = "${runevault.bank-settlement.poll-ms:30000}",
   fixedDelayString = "${runevault.bank-settlement.poll-ms:30000}")
 public void checkPendingTransfers() {
  var pending = transactions.findTop20ByStatusAndBankReceiptIdIsNotNullAndBankSubmittedAtAfterOrderByBankSubmittedAtAsc(
   Transaction.Status.BANK_PENDING, Instant.now().minus(window));
  for (Transaction transaction : pending) {
   try {
    reconciliation.reconcile(transaction.getTransactionId());
   } catch (RuntimeException e) {
    log.warn("Settlement check for {} failed ({})", transaction.getTransactionId(), e.getClass().getSimpleName());
   }
  }
 }
}
