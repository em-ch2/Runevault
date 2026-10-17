package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"runevault.bridge-token=test-bridge-token-with-enough-characters", "runevault.requests-per-minute=10000"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class DeviceAuthorizationTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 private static final String GLYPHS = "0-2-1-2-1";
 private static final String KEY = randomKey();
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;

 private static String randomKey() {
  byte[] bytes = new byte[32];
  new SecureRandom().nextBytes(bytes);
  return HexFormat.of().formatHex(bytes);
 }

 @DynamicPropertySource
 static void config(DynamicPropertyRegistry registry) {
  registry.add("runevault.pico-key", () -> KEY);
  registry.add("runevault.pico-device-id", () -> "slab-01");
  registry.add("spring.datasource.url", () -> "jdbc:h2:mem:device-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
 }

 @BeforeEach void reset() {
  transactions.deleteAll();
  devices.deleteAll();
 }

 private String pending() throws Exception {
  var response = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(
    Map.of("device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", GLYPHS))))
   .andExpect(status().isCreated()).andReturn();
  return mapper.readTree(response.getResponse().getContentAsString()).get("tx_id").asText();
 }

 private static String signature(String key, String text) throws Exception {
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
 }

 private DeviceApi.Authorization signed(String id, long counter, long amount, String dest, String glyphs) throws Exception {
  // Independent canonical construction, rather than signing via production helper.
  String text = "v1|slab-01|" + counter + "|" + id + "|" + amount + "|" + dest + "|" + glyphs;
  return new DeviceApi.Authorization(1, "slab-01", counter, id, amount, dest, glyphs, signature(KEY, text));
 }

 private ResultActions authorize(DeviceApi.Authorization request) throws Exception {
  return mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(request)));
 }

 @Test void exactCanonicalFormat() {
  var request = new DeviceApi.Authorization(1, "slab-01", 2L, "abc123", 50000L, "acct-999", GLYPHS, "0".repeat(64));
  assertEquals("v1|slab-01|2|abc123|50000|acct-999|0-2-1-2-1", DeviceAuthorizationService.signingText(request));
 }

 @Test
 @EnabledIfEnvironmentVariable(named = "PICO_TEST_VECTOR_KEY", matches = ".+")
 void suppliedFirmwareTestVector() throws Exception {
  assertEquals("16e6adc714f04bf58c98d0ffc343b31ecd560ed418ad9d1158d6321c2021563b",
   signature(System.getenv("PICO_TEST_VECTOR_KEY"), "v1|slab-01|2|abc123|50000|acct-999|0-2-1-2-1"));
 }

 @Test void successfulAuthorizationPersists() throws Exception {
  String id = pending();
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS))
   .andExpect(status().isOk()).andExpect(jsonPath("transactionId").value(id))
   .andExpect(jsonPath("status").value("SIMULATED"));
  assertEquals(Transaction.Status.SIMULATED, transactions.findById(id).orElseThrow().getStatus());
  assertEquals(2, devices.findById("slab-01").orElseThrow().getLastCounter());
 }

 @Test void invalidSignatureDoesNotChangeDeviceOrTransaction() throws Exception {
  String id = pending();
  authorize(new DeviceApi.Authorization(1, "slab-01", 2L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64)))
   .andExpect(status().isUnauthorized());
  var device = devices.findById("slab-01").orElseThrow();
  assertEquals(-1, device.getLastCounter());
  assertEquals(0, device.getFailedAttempts());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
 }

 @Test void counterReplayAndConsumedTransactionsAreRejected() throws Exception {
  String id = pending();
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  String next = pending();
  authorize(signed(next, 1, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(id, 3, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(next, 4, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
 }

 @Test void fifthWrongGlyphLocksDeviceAcrossTransactions() throws Exception {
  String id = pending();
  String other = pending();
  for (int counter = 1; counter <= 5; counter++) {
   authorize(signed(id, counter, 50000, "acct-999", "0-0"))
    .andExpect(status().is(counter == 5 ? 423 : 403));
  }
  var device = devices.findById("slab-01").orElseThrow();
  assertTrue(device.isLocked());
  assertEquals(5, device.getLastCounter());
  authorize(signed(other, 6, 50000, "acct-999", GLYPHS)).andExpect(status().is(423));
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
 }

 @Test void wrongGlyphReplayDoesNotCountTwiceAndSuccessResetsAttempts() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50000, "acct-999", "0")).andExpect(status().isForbidden());
  authorize(signed(id, 1, 50000, "acct-999", "0")).andExpect(status().isConflict());
  assertEquals(1, devices.findById("slab-01").orElseThrow().getFailedAttempts());
  authorize(signed(id, 2, 50000, "acct-999", GLYPHS)).andExpect(status().isOk());
  assertEquals(0, devices.findById("slab-01").orElseThrow().getFailedAttempts());
 }

 @Test void pendingAmountDestinationAndIdMustMatch() throws Exception {
  String id = pending();
  authorize(signed(id, 1, 50001, "acct-999", GLYPHS)).andExpect(status().isConflict());
  authorize(signed(id, 2, 50000, "other", GLYPHS)).andExpect(status().isConflict());
  authorize(signed("missing", 3, 50000, "acct-999", GLYPHS)).andExpect(status().isConflict());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
 }

 @Test void bridgeTokenRequiredOnBothEndpoints() throws Exception {
  for (String path : new String[]{"/api/device/authorize", "/api/device/transactions"}) {
   mvc.perform(post(path).contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
   mvc.perform(post(path).header("X-Bridge-Token", "wrong").contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized());
  }
 }

 @Test void malformedPayloadRejected() throws Exception {
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
  String id = pending();
  authorize(new DeviceApi.Authorization(2, "slab-01", 1L, id, 50000L, "acct-999", GLYPHS, "0".repeat(64)))
   .andExpect(status().isBadRequest());
  authorize(signed(id, 1, 50000, "acct-999", "0-3"))
   .andExpect(status().isBadRequest());
 }
}
