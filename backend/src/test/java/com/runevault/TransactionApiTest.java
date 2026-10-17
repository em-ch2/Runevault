package com.runevault;

import com.runevault.banking.FakeBankGateway;
import com.runevault.banking.BankReceipt;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TransactionApiTest {
 private final AtomicInteger transfers = new AtomicInteger();
 private final FakeBankGateway bank = new FakeBankGateway();
 private final TransactionApi api = new TransactionApi("test-key", "local", (id, amount, currency, destination) -> {
  transfers.incrementAndGet();
  return bank.transfer(id, amount, currency, destination);
 });
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
  var result = api.approve(approval);
  assertEquals(true,result.get("signatureVerified"));
  assertEquals("SIMULATED",result.get("status"));
  assertEquals(new BankReceipt("fake-" + c.challengeId(), c.challengeId(), "fake", "SIMULATED", 2500, "USD", "harbor"), result.get("receipt"));
  assertEquals(1,transfers.get());
  var error = assertThrows(ResponseStatusException.class,() -> api.approve(approval));
  assertEquals(409,error.getStatusCode().value());
  assertEquals(1,transfers.get());
 }
 @Test void tamperedAmountFailsWithoutConsumingChallenge() throws Exception {
  var c = challenge();
  var bad = new TransactionApi.Approval(c.challengeId(),sign(c.signingText().replace("2500","2501")));
  var error = assertThrows(ResponseStatusException.class,() -> api.approve(bad));
  assertEquals(401,error.getStatusCode().value());
  assertEquals(0,transfers.get());
  assertEquals(true,api.approve(new TransactionApi.Approval(c.challengeId(),sign(c.signingText()))).get("signatureVerified"));
  assertEquals(1,transfers.get());
 }
 @Test void signingTextMatchesProtocol() {
  var c = challenge();
  assertEquals(String.join("\n", "runevault-v1", c.challengeId(), "pico-demo-1", "2500", "USD", "harbor", "ABCD", Long.toString(c.expiresAt())), c.signingText());
 }
 @Test void productionModeRefused() {
  assertThrows(IllegalStateException.class,() -> new TransactionApi("key","production", new FakeBankGateway()));
 }
}
