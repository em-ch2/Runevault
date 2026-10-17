package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class TransactionApiTest {
 private final TransactionRepository repository = mock(TransactionRepository.class);
 private final TransactionApi api = new TransactionApi("test-key", "local", repository);
 private TransactionApi.Challenge challenge() {
  return api.challenge(new TransactionApi.Intent("pico-demo-1",2500,"harbor","ABCD"));
 }
 private String sign(String text) throws Exception {
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
 }
 @Test void acceptsOnceAndRejectsReplay() throws Exception {
  var c = challenge();
  var approval = new TransactionApi.Approval(c.challengeId(),sign(c.signingText()));
  assertEquals(true,api.approve(approval).get("signatureVerified"));
  var error = assertThrows(ResponseStatusException.class,() -> api.approve(approval));
  assertEquals(409,error.getStatusCode().value());
  var saved = ArgumentCaptor.forClass(Transaction.class);
  verify(repository, times(1)).saveAndFlush(saved.capture());
  assertEquals(c.challengeId(), saved.getValue().getTransactionId());
  assertEquals(2500, saved.getValue().getAmountMinor());
  assertEquals("harbor", saved.getValue().getDestination());
  assertEquals(Transaction.Status.SIMULATED, saved.getValue().getStatus());
 }
 @Test void tamperedAmountFailsWithoutConsumingChallenge() throws Exception {
  var c = challenge();
  var bad = new TransactionApi.Approval(c.challengeId(),sign(c.signingText().replace("2500","2501")));
  var error = assertThrows(ResponseStatusException.class,() -> api.approve(bad));
  assertEquals(401,error.getStatusCode().value());
  verifyNoInteractions(repository);
  assertEquals(true,api.approve(new TransactionApi.Approval(c.challengeId(),sign(c.signingText()))).get("signatureVerified"));
 }
 @Test void productionModeRefused() {
  assertThrows(IllegalStateException.class,() -> new TransactionApi("key","production", repository));
 }
 @Test void failedSaveLeavesChallengeAvailableForRetry() throws Exception {
  var c = challenge();
  var approval = new TransactionApi.Approval(c.challengeId(), sign(c.signingText()));
  when(repository.saveAndFlush(any(Transaction.class)))
   .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"))
   .thenAnswer(invocation -> invocation.getArgument(0));
  assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> api.approve(approval));
  assertEquals("SIMULATED", api.approve(approval).get("status"));
 }
}
