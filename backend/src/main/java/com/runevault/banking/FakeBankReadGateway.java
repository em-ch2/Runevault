package com.runevault.banking;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test and simulator data; never claims a real Nessie account exists. */
@Component
@Profile("test | simulator")
public class FakeBankReadGateway implements BankReadGateway {
 @Override public boolean destinationExists(String accountId) { return true; }

 @Override public TransferHistory transferHistory(String accountId) { return TransferHistory.empty(); }

 @Override public long sourceBalanceMinor() { return 1_000_000L; }
}
