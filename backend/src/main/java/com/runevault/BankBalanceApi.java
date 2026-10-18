package com.runevault;

import com.runevault.banking.BankGateway;
import com.runevault.banking.BankReceipt;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The treasury balance as Nessie reports it now, beside the remaining balance Runevault calculates. The Nessie
 * sandbox records transfers (and marks some completed) without ever deducting them, so the remaining balance
 * is the reported balance minus every transfer the bank accepted or completed, across all users of the shared
 * source account. It is always shown labelled as calculated. Same assumption as the per-transfer expected
 * balance in DashboardTransactionApi.
 */
@RestController
@RequestMapping("/api/bank")
public class BankBalanceApi {
 public record BankBalance(Long reportedBalanceMinor, long sentTransfers, long sentTransfersMinor,
   Long remainingBalanceMinor, Instant asOf) {}

 private final BankGateway bank;
 private final TransactionRepository transactions;

 public BankBalanceApi(BankGateway bank, TransactionRepository transactions) {
  this.bank = bank;
  this.transactions = transactions;
 }

 @GetMapping("/balance")
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public BankBalance balance() {
  Object[] row = transactions.sentTransfers("nessie",
   List.of(BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Outcome.COMPLETED)).get(0);
  long count = ((Number) row[0]).longValue();
  long sentMinor = ((Number) row[1]).longValue();
  Long reported = bank.sourceBalanceMinor().orElse(null);
  return new BankBalance(reported, count, sentMinor, reported == null ? null : reported - sentMinor, Instant.now());
 }
}
