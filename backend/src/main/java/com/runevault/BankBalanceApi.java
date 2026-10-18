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
 * The treasury balance as Nessie reports it, beside the balance Runevault expects after its own transfers.
 * The Nessie sandbox records transfers without applying them to balances, so the expected figure is
 * calculated here and always labelled as such: reported balance minus accepted transfers whose deduction
 * the bank has not confirmed. A transfer the bank did confirm (a recorded new balance) is not subtracted again.
 */
@RestController
@RequestMapping("/api/bank")
public class BankBalanceApi {
 public record BankBalance(Long reportedBalanceMinor, long unappliedTransfers, long unappliedTransfersMinor,
   Long expectedBalanceMinor, Instant asOf) {}

 private final BankGateway bank;
 private final TransactionRepository transactions;

 public BankBalanceApi(BankGateway bank, TransactionRepository transactions) {
  this.bank = bank;
  this.transactions = transactions;
 }

 @GetMapping("/balance")
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public BankBalance balance() {
  Object[] row = transactions.unconfirmedTransfers("nessie",
   List.of(BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Outcome.COMPLETED)).get(0);
  long count = ((Number) row[0]).longValue();
  long sentMinor = ((Number) row[1]).longValue();
  Long reported = bank.sourceBalanceMinor().orElse(null);
  return new BankBalance(reported, count, sentMinor, reported == null ? null : reported - sentMinor, Instant.now());
 }
}
