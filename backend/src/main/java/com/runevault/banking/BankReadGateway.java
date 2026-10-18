package com.runevault.banking;

/** Read-only bank data used before creating and assessing a transfer. */
public interface BankReadGateway {
 boolean destinationExists(String accountId);

 TransferHistory transferHistory(String accountId);

 /** Current source-account balance in cents, or Unavailable when it cannot be verified. */
 long verifiedSourceBalanceMinor();

 record TransferHistory(int observedTransfers, int recentThirtyDays, long totalAmountMinor,
   long largestAmountMinor, int failedTransfers, boolean truncated) {
  public static TransferHistory empty() { return new TransferHistory(0, 0, 0, 0, 0, false); }
 }

 class Unavailable extends RuntimeException {
  public Unavailable() { super("Bank account data is unavailable"); }
 }
}
