package com.runevault;

import com.runevault.banking.BankReadGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DestinationPreflightTest {
 private final TransactionRepository transactions = mock(TransactionRepository.class);
 private final DeviceStateRepository devices = mock(DeviceStateRepository.class);
 private final AuditEventRepository audits = mock(AuditEventRepository.class);
 private final BankReadGateway bankReads = mock(BankReadGateway.class);
 private final DeviceAuthorizationService service = new DeviceAuthorizationService(transactions, devices,
  audits, new TransactionPolicy(100000000, List.of("payee-1"), 5), bankReads, "slab-01", "test-key");
 private final DeviceApi.Pending request = new DeviceApi.Pending("slab-01", 50000L, "payee-1", "0-1-2");

 @Test void unknownDestinationIsRejectedBeforePersisting() {
  when(bankReads.destinationExists("payee-1")).thenReturn(false);
  assertEquals(400, service.createPending(request).getStatusCode().value());
  verifyNoInteractions(transactions, devices);
 }

 @Test void lookupOutageDoesNotCreateATransfer() {
  when(bankReads.destinationExists("payee-1")).thenThrow(new BankReadGateway.Unavailable());
  assertEquals(503, service.createPending(request).getStatusCode().value());
  verifyNoInteractions(transactions, devices);
 }
}
