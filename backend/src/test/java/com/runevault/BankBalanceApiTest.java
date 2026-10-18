package com.runevault;

import com.runevault.banking.BankGateway;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BankBalanceApiTest {
 private final BankGateway bank = mock(BankGateway.class);
 private final TransactionRepository transactions = mock(TransactionRepository.class);
 private final BankBalanceApi api = new BankBalanceApi(bank, transactions);

 private void unapplied(long count, long minor) {
  when(transactions.unconfirmedTransfers(eq("nessie"), anyCollection()))
   .thenReturn(List.<Object[]>of(new Object[] {count, minor}));
 }

 @Test void expectedBalanceSubtractsTransfersTheBankHasNotApplied() {
  when(bank.sourceBalanceMinor()).thenReturn(Optional.of(50_000_000L));
  unapplied(3, 150_350);
  var balance = api.balance();
  assertEquals(50_000_000L, balance.reportedBalanceMinor());
  assertEquals(3, balance.unappliedTransfers());
  assertEquals(150_350, balance.unappliedTransfersMinor());
  assertEquals(49_849_650L, balance.expectedBalanceMinor());
 }

 @Test void noExpectedBalanceWithoutABankReading() {
  when(bank.sourceBalanceMinor()).thenReturn(Optional.empty());
  unapplied(2, 1_000);
  var balance = api.balance();
  assertNull(balance.reportedBalanceMinor());
  assertNull(balance.expectedBalanceMinor());
  assertEquals(1_000, balance.unappliedTransfersMinor());
 }
}
