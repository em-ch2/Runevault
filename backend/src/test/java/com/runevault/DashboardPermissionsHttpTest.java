package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.banking.BankReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-permission-tests",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:dashboard-permissions-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class DashboardPermissionsHttpTest {
 private static final String BODY = """
  {"device":"slab-01","amount":50000,"dest":"acct-999","requesterSubject":"spoofed"}
  """;
 private static final String BRIDGE_BODY = """
  {"device":"slab-01","amount":50000,"dest":"acct-999","glyphs":"0-2-1-2-1"}
  """;
 @Autowired MockMvc mvc;
 @Autowired TransactionRepository transactions;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @Autowired com.runevault.events.PipelineEventWriter pipelineWriter;
 @Autowired ObjectMapper mapper;
 @Autowired com.runevault.transactions.BankOutcomeService outcomes;
 @MockBean JwtDecoder decoder;
 @MockBean com.runevault.ApprovalCodeGenerator glyphGenerator;

 @BeforeEach void prepare() {
  auditEvents.deleteAll();
  transactions.deleteAll();
  devices.deleteAll();
  when(glyphGenerator.generate()).thenReturn("0-2-1-2-1");
  token("employee", "auth0|employee", "create:transactions", "read:transactions");
  token("viewer", "auth0|viewer", "read:transactions");
  token("creator", "auth0|creator", "create:transactions");
  token("admin", "auth0|admin", "create:transactions", "read:transactions", "admin:platform");
  token("auditor", "auth0|auditor", "read:audit");
 }

 @Test void auditorReadsSafeCompanyAuditsButCannotModifyResources() throws Exception {
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.DEVICE_REVOCATION, "slab-01", "tx-one",
   AuditEvent.Outcome.APPROVED, AuditEvent.Reason.REVOKED_BY_ADMIN, "auth0|admin"));
  mvc.perform(get("/api/audit-events?transactionId=tx-one&outcome=APPROVED")
   .header("Authorization", "Bearer auditor"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
   .andExpect(jsonPath("$.content[0].id").isNumber())
   .andExpect(jsonPath("$.content[0].timestamp").isNotEmpty())
   .andExpect(jsonPath("$.content[0].actorSubject").value("auth0|admin"))
   .andExpect(jsonPath("$.content[0].glyphs").doesNotExist())
   .andExpect(jsonPath("$.content[0].token").doesNotExist());
  mvc.perform(get("/api/audit-events").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/audit-events").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer auditor")
   .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
  mvc.perform(post("/api/transactions/tx-one/approve").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/devices/slab-01/revoke").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/transactions/tx-one/resume").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/transactions/tx-one/reconcile").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/audit-events").header("Authorization", "Bearer auditor"))
   .andExpect(status().isForbidden());
 }

 @Test void companyPolicyMetricsCountOnlyGeminiDecisionsAndRequireAuditPermission() throws Exception {
  var baseline = mvc.perform(get("/api/metrics/policy-decisions?minutes=5")
   .header("Authorization", "Bearer auditor")).andExpect(status().isOk()).andReturn();
  var before = mapper.readTree(baseline.getResponse().getContentAsString());
  Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60);
  for (String[] event : new String[][] {
   {"tx-one", "PENDING", "PENDING"}, {"tx-one", "VERIFIED", "APPROVED"},
   {"tx-one", "GEMINI", "APPROVED"}, {"tx-one", "EXECUTED", "COMPLETED"},
   {"tx-two", "VERIFIED", "REJECTED"}, {"tx-two", "GEMINI", "REJECTED"}
  }) pipelineWriter.write(new com.runevault.events.PipelineEventWriter.Row(
   java.util.UUID.randomUUID(), at, event[0], event[1], event[2], null, null, null, null, null));

  mvc.perform(get("/api/metrics/policy-decisions?minutes=5"))
   .andExpect(status().isUnauthorized());
  mvc.perform(get("/api/metrics/policy-decisions?minutes=5").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  var response = mvc.perform(get("/api/metrics/policy-decisions?minutes=5").header("Authorization", "Bearer auditor"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("raw-test-events"))
   .andExpect(jsonPath("$.measure").value("gemini-policy-decisions"))
   .andExpect(jsonPath("$.minutes", org.hamcrest.Matchers.hasSize(5)))
   .andExpect(jsonPath("$.transactionId").doesNotExist()).andReturn();
  var after = mapper.readTree(response.getResponse().getContentAsString());
  assertEquals(before.path("approved").asLong() + 1, after.path("approved").asLong());
  assertEquals(before.path("blocked").asLong() + 1, after.path("blocked").asLong());
  mvc.perform(get("/api/metrics/policy-decisions?minutes=5").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk());
  mvc.perform(get("/api/metrics/policy-decisions?minutes=1441").header("Authorization", "Bearer auditor"))
   .andExpect(status().isBadRequest());
 }

 @Test void simulatorRoutesAreUnavailableButHealthAndDashboardRemain() throws Exception {
  mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("status").value("ok"));
  mvc.perform(post("/api/challenges").header("Authorization", "Bearer employee")
   .header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
   .contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post("/api/approvals").header("Authorization", "Bearer employee")
   .header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
   .contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk());
 }

 private void token(String value, String subject, String... permissions) {
  Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256")
   .issuer("https://issuer.example.test/").audience(List.of("https://api.runevault"))
   .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
   .claim("permissions", List.of(permissions)).build();
  when(decoder.decode(value)).thenReturn(jwt);
 }

 @Test void permissionAndVerifiedSubjectControlDashboardRoutes() throws Exception {
  mvc.perform(post("/api/transactions").contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/transactions").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer viewer")
    .contentType("application/json").content(BODY))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer creator"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer employee")
    .contentType("application/json").content(BODY))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
   .andExpect(jsonPath("$.glyphs").value("0-2-1-2-1"));
  assertEquals("auth0|employee", transactions.findAll().get(0).getRequesterSubject());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer viewer"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].requesterSubject").value("auth0|employee"))
   .andExpect(jsonPath("$.content[0].approvalStepUp").value(org.hamcrest.Matchers.nullValue()))
   .andExpect(jsonPath("$.content[0].expectedGlyphs").doesNotExist());
  mvc.perform(get("/api/admin/audit-events").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/audit-events").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk());
  mvc.perform(post("/api/admin/transactions/example/reconcile").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/transactions/example/resume").header("Authorization", "Bearer employee"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/transactions/missing/reconcile").header("Authorization", "Bearer admin"))
   .andExpect(status().isNotFound());
  mvc.perform(post("/api/admin/transactions/missing/resume").header("Authorization", "Bearer admin"))
   .andExpect(status().isNotFound());
 }

 @Test void twoUsersSeeOnlyTheirOwnTransactions() throws Exception {
  transactions.save(Transaction.pending("alice-tx", 125, "acct-999", "slab-01", "0-2-1", "auth0|employee"));
  transactions.save(Transaction.pending("bob-tx", 225, "acct-999", "slab-02", "1-2-0", "auth0|admin"));
  transactions.save(Transaction.pending("bridge-tx", 325, "acct-999", "slab-03", "2-1-0", null));
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
   .andExpect(jsonPath("$.content[0].transactionId").value("alice-tx"))
   .andExpect(jsonPath("$.content[0].approverSubject").value(org.hamcrest.Matchers.nullValue()))
   .andExpect(jsonPath("$.content[0].expiresAt").isNotEmpty());
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
   .andExpect(jsonPath("$.content[0].transactionId").value("bob-tx"));
  pipelineWriter.write(new com.runevault.events.PipelineEventWriter.Row(
   java.util.UUID.randomUUID(), Instant.now(), "alice-tx", "GEMINI", "APPROVED", "VERIFIED", null, null, null, null));
  pipelineWriter.write(new com.runevault.events.PipelineEventWriter.Row(
   java.util.UUID.randomUUID(), Instant.now(), "bob-tx", "GEMINI", "REJECTED", "VERIFIED", null, null, null, null));
  mvc.perform(get("/api/metrics/pipeline").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(1))
   .andExpect(jsonPath("$.blocked").value(0));
  mvc.perform(get("/api/metrics/pipeline").header("Authorization", "Bearer admin"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(0))
   .andExpect(jsonPath("$.blocked").value(1));
 }

 @Test void bridgeEndpointsStillRequireBridgeToken() throws Exception {
  mvc.perform(post("/api/device/transactions").header("Authorization", "Bearer admin")
    .contentType("application/json").content(BODY))
   .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(BRIDGE_BODY))
   .andExpect(status().isCreated());
  assertNull(transactions.findAll().get(0).getRequesterSubject());
 }

 @Test void dashboardExposesPhysicalVerificationTime() throws Exception {
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer employee")
    .contentType("application/json").content(BODY)).andExpect(status().isCreated());
  String id = transactions.findAll().get(0).getTransactionId();
  String signed = "v1|slab-01|1|" + id + "|50000|acct-999|0-2-1-2-1";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-pico-key-for-permission-tests".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  var approval = new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999",
   "0-2-1-2-1", HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", "test-bridge-token-with-enough-characters")
    .contentType("application/json").content(mapper.writeValueAsString(approval)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].status").value("VERIFIED"))
   .andExpect(jsonPath("$.content[0].verifiedAt").isNotEmpty());
 }

 private void settled(String id, long amount, Instant at, BankReceipt.Outcome outcome, Long reportedBalance) {
  Transaction tx = Transaction.pending(id, amount, "acct-999", "slab-01", "0-1-2", "auth0|employee");
  tx.verifyPhysicalApproval(at);
  tx.claimForExecution(at);
  transactions.saveAndFlush(tx);
  outcomes.record(id, new BankReceipt("r-" + id, id, "nessie",
   outcome == BankReceipt.Outcome.COMPLETED ? "completed" : "pending", amount, "USD", "acct-999", outcome,
   outcome == BankReceipt.Outcome.COMPLETED ? BankReceipt.Reason.CONFIRMED_COMPLETED : BankReceipt.Reason.ACCEPTED_PENDING,
   at, reportedBalance));
 }

 @Test void expectedBalanceSubtractsEveryCompletedTransferFromTheFlatSandboxBalance() throws Exception {
  // The sandbox reports $500,000.00 throughout; two $500.00 transfers complete, a third is still pending.
  settled("first", 50000, Instant.now().minusSeconds(60), BankReceipt.Outcome.COMPLETED, 50000000L);
  settled("second", 50000, Instant.now().minusSeconds(30), BankReceipt.Outcome.COMPLETED, 50000000L);
  settled("waiting", 50000, Instant.now(), BankReceipt.Outcome.ACCEPTED_PENDING, null);
  mvc.perform(get("/api/transactions").header("Authorization", "Bearer employee"))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.content[?(@.transactionId=='first')].expectedBalanceMinor").value(49950000))
   .andExpect(jsonPath("$.content[?(@.transactionId=='second')].expectedBalanceMinor").value(49900000))
   .andExpect(jsonPath("$.content[?(@.transactionId=='second')].newBalanceMinor").value(50000000))
   .andExpect(jsonPath("$.content[?(@.transactionId=='waiting')].expectedBalanceMinor").value(org.hamcrest.Matchers.contains((Object) null)));
 }

 @Test void creatorRelaysPicoApprovalWithoutBridgeToken() throws Exception {
  mvc.perform(post("/api/transactions").header("Authorization", "Bearer employee")
    .contentType("application/json").content(BODY)).andExpect(status().isCreated());
  String id = transactions.findAll().get(0).getTransactionId();
  String signed = "v1|slab-01|1|" + id + "|50000|acct-999|0-2-1-2-1";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("test-pico-key-for-permission-tests".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  String approval = mapper.writeValueAsString(new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999",
   "0-2-1-2-1", HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)))));
  String path = "/api/transactions/" + id + "/authorize";
  mvc.perform(post(path).contentType("application/json").content(approval))
   .andExpect(status().isUnauthorized());
  mvc.perform(post(path).header("Authorization", "Bearer viewer").contentType("application/json").content(approval))
   .andExpect(status().isForbidden());
  // Another user's transfer and a body for a different transaction both look missing.
  mvc.perform(post(path).header("Authorization", "Bearer admin").contentType("application/json").content(approval))
   .andExpect(status().isNotFound());
  mvc.perform(post("/api/transactions/other-tx/authorize").header("Authorization", "Bearer employee")
    .contentType("application/json").content(approval))
   .andExpect(status().isNotFound());
  assertEquals(Transaction.Status.PENDING, transactions.findById(id).orElseThrow().getStatus());
  mvc.perform(post(path).header("Authorization", "Bearer employee").contentType("application/json").content(approval))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
  // The signed counter is consumed, so relaying it again is a replay.
  mvc.perform(post(path).header("Authorization", "Bearer employee").contentType("application/json").content(approval))
   .andExpect(status().is4xxClientError());
 }
}
