package com.runevault;

import com.runevault.banking.FakeBankGateway;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransactionApiTest {
 private final TransactionRepository repository = mock(TransactionRepository.class);
 private final FakeBankGateway fakeBank = spy(new FakeBankGateway());
 private final TransactionApi api = new TransactionApi("test-key", fakeBank, repository,
  new TransactionPolicy(100000000, List.of("harbor"), 5), mock(AuditEventRepository.class));

 private TransactionApi.Challenge challenge() {
  return api.challenge(new TransactionApi.Intent("pico-demo-1", 2500, "harbor", "ABCD"));
 }
 private String sign(String text) throws Exception {
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
 }

 @Test void persistsClearlyLabeledSimulationAndRejectsReplay() throws Exception {
  when(repository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
  var c = challenge();
  var approval = new TransactionApi.Approval(c.challengeId(), sign(c.signingText()));
  var response = api.approve(approval);
  assertEquals("SIMULATED", response.get("status"));
  assertEquals("hardware-simulated", response.get("mode"));
  assertEquals("local-fake", response.get("bankingMode"));
  verify(repository).saveAndFlush(argThat(t -> t.getStatus() == Transaction.Status.SIMULATED
   && t.getVerifiedAt() == null && t.getDeviceId() == null));
  verify(fakeBank).transfer(c.challengeId(), 2500, "USD", "harbor");
  assertEquals(409, assertThrows(ResponseStatusException.class, () -> api.approve(approval)).getStatusCode().value());
  verifyNoMoreInteractions(fakeBank);
 }

 @Test void tamperedSignatureDoesNotReachBankOrStorage() throws Exception {
  var c = challenge();
  var bad = new TransactionApi.Approval(c.challengeId(), sign(c.signingText().replace("2500", "2501")));
  assertEquals(401, assertThrows(ResponseStatusException.class, () -> api.approve(bad)).getStatusCode().value());
  verifyNoInteractions(fakeBank, repository);
 }

 @Test void signingTextMatchesProtocol() {
  var c = challenge();
  assertEquals(String.join("\n", "runevault-v1", c.challengeId(), "pico-demo-1", "2500", "USD",
   "harbor", "ABCD", Long.toString(c.expiresAt())), c.signingText());
 }

 @Test void failedSaveLeavesChallengeAvailableForRetry() throws Exception {
  var c = challenge();
  var approval = new TransactionApi.Approval(c.challengeId(), sign(c.signingText()));
  when(repository.saveAndFlush(any(Transaction.class)))
   .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
   .thenAnswer(invocation -> invocation.getArgument(0));
  assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> api.approve(approval));
  verifyNoInteractions(fakeBank);
  assertEquals("SIMULATED", api.approve(approval).get("status"));
  verify(fakeBank).transfer(c.challengeId(), 2500, "USD", "harbor");
  verify(repository, times(2)).saveAndFlush(any(Transaction.class));
 }
}
