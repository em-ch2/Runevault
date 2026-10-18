package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.lore.LoreAuditService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=maker-checker-test-pico-key",
 "runevault.auto-execute=true",
 "runevault.approval.required=true",
 "runevault.approval.step-up-amount-minor=100000",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:maker-checker-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class MakerCheckerHttpTest {
 private static final String BRIDGE = "test-bridge-token-with-enough-characters";
 private static final String GLYPHS = "0-2-1-2-1";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @MockBean JwtDecoder decoder;
 @MockBean ApprovalCodeGenerator glyphGenerator;
 @MockBean LoreAuditService lore;
 @SpyBean com.runevault.banking.FakeBankGateway bank;
 private long counter;

 @BeforeEach void prepare() throws Exception {
  auditEvents.deleteAll(); transactions.deleteAll(); devices.deleteAll();
  reset(bank, lore);
  counter = 0;
  when(glyphGenerator.generate()).thenReturn(GLYPHS);
  when(lore.audit(any(Transaction.class), anyString())).thenReturn(mapper.readTree("""
   {"lore_translation":"Safe passage.","translation_confidence":0.9,"risk_score":10,
    "risk_flags":[],"risk_explanation":"Routine.","security_message":"Looks routine."}
   """));
  token("maker", "auth0|maker", null, "create:transactions", "read:transactions");
  token("maker-as-cfo", "auth0|maker", List.of("pwd", "mfa"), "create:transactions", "read:transactions", "approve:transactions");
  token("cfo", "auth0|cfo", null, "read:transactions", "approve:transactions");
  token("cfo-mfa", "auth0|cfo", List.of("pwd", "mfa"), "read:transactions", "approve:transactions");
 }

 @Test void signedTransferWaitsForASecondPersonWhoReleasesItOnce() throws Exception {
  String id = signedAndReviewed(50000);
  verifyNoInteractions(bank);
  mvc.perform(get("/api/transactions/approvals").header("Authorization", "Bearer maker"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer maker"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions/approvals").header("Authorization", "Bearer cfo"))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.content[0].transactionId").value(id))
   .andExpect(jsonPath("$.content[0].awaitingApproval").value(true))
   .andExpect(jsonPath("$.content[0].stepUpRequired").value(false));

  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer cfo"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));
  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer cfo"))
   .andExpect(status().isConflict());

  verify(bank, times(1)).transfer(id, 50000L, "USD", "acct-999");
  Transaction saved = transactions.findById(id).orElseThrow();
  assertEquals("auth0|cfo", saved.getApproverSubject());
  assertNotNull(saved.getApprovedAt());
  assertTrue(auditEvents.findAll().stream().anyMatch(e -> e.getReason() == AuditEvent.Reason.EXECUTION_CLAIMED
   && "auth0|cfo".equals(e.getActorSubject())));
 }

 @Test void requesterCannotApproveTheirOwnTransferEvenWithTheApproverRole() throws Exception {
  String id = signedAndReviewed(50000);
  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer maker-as-cfo"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("self_approval"));
  verifyNoInteractions(bank);
  Transaction saved = transactions.findById(id).orElseThrow();
  assertEquals(Transaction.Status.VERIFIED, saved.getStatus());
  assertNull(saved.getExecutionClaimedAt());
  assertTrue(auditEvents.findAll().stream().anyMatch(e -> e.getReason() == AuditEvent.Reason.SELF_APPROVAL
   && e.getOutcome() == AuditEvent.Outcome.REJECTED && "auth0|maker".equals(e.getActorSubject())));
 }

 @Test void largeTransfersNeedAnMfaBackedApprovalToken() throws Exception {
  String id = signedAndReviewed(150000);
  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer cfo"))
   .andExpect(status().isUnauthorized())
   .andExpect(header().string("WWW-Authenticate", containsString("insufficient_user_authentication")))
   .andExpect(jsonPath("$.error").value("step_up_required"));
  verifyNoInteractions(bank);
  mvc.perform(post("/api/transactions/" + id + "/approve").header("Authorization", "Bearer cfo-mfa"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));
  assertTrue(transactions.findById(id).orElseThrow().getApprovalStepUp());
  verify(bank, times(1)).transfer(id, 150000L, "USD", "acct-999");
 }

 private String signedAndReviewed(long amount) throws Exception {
  var created = mvc.perform(post("/api/transactions").header("Authorization", "Bearer maker")
    .contentType("application/json").content(mapper.writeValueAsString(Map.of(
     "device", "slab-01", "amount", amount, "dest", "acct-999"))))
   .andExpect(status().isCreated()).andReturn();
  String id = mapper.readTree(created.getResponse().getContentAsString()).get("tx_id").asText();
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", BRIDGE)
    .contentType("application/json").content(signed(id, amount, ++counter)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.execution.status").value("AWAITING_APPROVAL"));
  return id;
 }

 private String signed(String id, long amount, long counter) throws Exception {
  String text = "v1|slab-01|" + counter + "|" + id + "|" + amount + "|acct-999|" + GLYPHS;
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("maker-checker-test-pico-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  String sig = HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  return mapper.writeValueAsString(new DeviceApi.Authorization(1, "slab-01", counter, id,
   amount, "acct-999", GLYPHS, sig));
 }

 private void token(String value, String subject, List<String> amr, String... permissions) {
  var builder = Jwt.withTokenValue(value).header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of(permissions));
  if (amr != null) builder.claim("amr", amr);
  when(decoder.decode(value)).thenReturn(builder.build());
 }

 private static org.hamcrest.Matcher<String> containsString(String part) {
  return org.hamcrest.Matchers.containsString(part);
 }
}
