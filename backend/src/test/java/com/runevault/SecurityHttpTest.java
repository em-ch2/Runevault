package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"runevault.bridge-token=test-bridge-token-with-enough-characters", "runevault.requests-per-minute=1000"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class SecurityHttpTest {
 @Autowired MockMvc mvc;
 @Autowired TransactionRepository transactions;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
 @org.springframework.beans.factory.annotation.Value("${runevault.device-secret}") String deviceSecret;
 @Test void approvedSimulationIsPersistedAndReplayRejected() throws Exception {
  String token = "test-bridge-token-with-enough-characters";
  var result = mvc.perform(post("/api/challenges").header("X-Bridge-Token", token)
   .contentType("application/json").content("""
    {"deviceId":"pico-demo-1","amountMinor":2500,"destination":"harbor","glyphs":"ABCD"}
    """)).andExpect(status().isOk()).andReturn();
  var challenge = mapper.readTree(result.getResponse().getContentAsString());
  String id = challenge.get("challengeId").asText();
  var mac = javax.crypto.Mac.getInstance("HmacSHA256");
  mac.init(new javax.crypto.spec.SecretKeySpec(deviceSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
  String signature = java.util.HexFormat.of().formatHex(mac.doFinal(challenge.get("signingText").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  String body = mapper.writeValueAsString(new TransactionApi.Approval(id, signature));
  mvc.perform(post("/api/approvals").header("X-Bridge-Token", token).contentType("application/json").content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("transactionId").value(id));
  var saved = transactions.findById(id).orElseThrow();
  org.junit.jupiter.api.Assertions.assertEquals(2500, saved.getAmountMinor());
  org.junit.jupiter.api.Assertions.assertEquals("harbor", saved.getDestination());
  org.junit.jupiter.api.Assertions.assertEquals(Transaction.Status.SIMULATED, saved.getStatus());
  org.junit.jupiter.api.Assertions.assertNull(saved.getVerifiedAt());
  org.junit.jupiter.api.Assertions.assertNotNull(saved.getCreatedAt());
  org.junit.jupiter.api.Assertions.assertNotNull(saved.getUpdatedAt());
  mvc.perform(post("/api/approvals").header("X-Bridge-Token", token).contentType("application/json").content(body))
   .andExpect(status().isConflict());
 }
 @Test void healthIsPublic() throws Exception {
  mvc.perform(get("/api/health")).andExpect(status().isOk());
 }
 @Test void bridgeCredentialRequired() throws Exception {
  mvc.perform(post("/api/challenges").contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized());
 }
 @Test void malformedIntentIsBadRequest() throws Exception {
  mvc.perform(post("/api/challenges").header("X-Bridge-Token","test-bridge-token-with-enough-characters")
   .contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
 }
 @Test void bridgeCanCreateChallenge() throws Exception {
  mvc.perform(post("/api/challenges").header("X-Bridge-Token","test-bridge-token-with-enough-characters")
   .contentType("application/json").content("""
    {"deviceId":"pico-demo-1","amountMinor":2500,"destination":"harbor","glyphs":"ABCD"}
    """))
   .andExpect(status().isOk()).andExpect(jsonPath("challengeId").isNotEmpty());
 }
 @Test void bridgeCannotAccessAdmin() throws Exception {
  mvc.perform(get("/api/admin/status").header("X-Bridge-Token","test-bridge-token-with-enough-characters"))
   .andExpect(status().isForbidden());
 }
}
