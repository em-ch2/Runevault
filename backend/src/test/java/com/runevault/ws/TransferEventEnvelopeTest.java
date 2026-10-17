package com.runevault.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.AuditEvent;
import com.runevault.Transaction;
import com.runevault.banking.BankReceipt;
import com.runevault.ws.TransferEventPublisher.ErrorState;
import com.runevault.ws.TransferEventPublisher.Outcome;
import com.runevault.ws.TransferEventPublisher.SafePayload;
import com.runevault.ws.TransferEventPublisher.Stage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferEventEnvelopeTest {
 private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

 private Transaction executed() {
  Transaction t = Transaction.pending("tx-env", 50000, "acct-999", "slab-01", "0-1-2", "auth0|owner");
  t.verifyPhysicalApproval(Instant.now());
  t.recordGeminiDecision(AuditEvent.Reason.GEMINI_APPROVED, """
   {"lore_translation":"The seal 0-1-2 was pressed; then 0 - 2 - 1 echoed.","translation_confidence":0.9,
    "risk_score":10,"risk_flags":["known destination"],"risk_explanation":"Routine.",
    "security_message":"Looks routine.","raw_debug":"should never travel"}
   """, "Risk score 10 is within the limit of 30.", Instant.now());
  t.claimForExecution(Instant.now());
  t.recordExecution(new BankReceipt("receipt-9", "tx-env", "nessie", "pending", 50000, "USD", "acct-999",
   BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, Instant.now(), 450000L), Instant.now());
  return t;
 }

 @Test void envelopeCarriesTheAgreedSafeFieldsFromCommittedRecords() throws Exception {
  JsonNode e = json.valueToTree(LiveTransferEventPublisher.envelope(executed(), Stage.BANK_PENDING,
   Outcome.ACCEPTED_PENDING, new SafePayload("ACCEPTED_PENDING", null)));
  assertEquals("BANK_PENDING", e.path("status").asText());
  JsonNode p = e.path("payload");
  assertEquals(0.9, p.path("translationConfidence").asDouble());
  assertEquals(10.0, p.path("riskScore").asDouble());
  assertEquals("known destination", p.path("riskFlags").path(0).asText());
  assertEquals("Looks routine.", p.path("securityMessage").asText());
  assertEquals("Risk score 10 is within the limit of 30.", p.path("explanation").asText());
  assertEquals("ACCEPTED_PENDING", p.path("reasonCode").asText());
  assertEquals("nessie", p.path("receipt").path("provider").asText());
  assertEquals("receipt-9", p.path("receipt").path("receiptId").asText());
  assertEquals("pending", p.path("receipt").path("providerStatus").asText());
  assertFalse(p.path("receipt").path("submittedAt").isNull());
  assertEquals(450000L, p.path("newBalanceMinor").asLong());
  assertTrue(p.path("errorState").isNull());
 }

 @Test void glyphSequencesAndRawFieldsNeverLeaveTheBackend() throws Exception {
  String wire = json.writeValueAsString(LiveTransferEventPublisher.envelope(executed(), Stage.EXECUTED,
   Outcome.COMPLETED, null));
  assertFalse(wire.contains("0-1-2"));
  assertFalse(wire.contains("0 - 2 - 1"));
  assertTrue(wire.contains("[sealed glyphs]"));
  for (String forbidden : new String[] {"raw_debug", "glyphs\"", "sig", "counter", "expectedGlyphs", "requesterSubject"})
   assertFalse(wire.contains(forbidden), forbidden);
 }

 @Test void blockedEventCarriesCodeAndErrorStateButNoReceipt() {
  Transaction t = Transaction.pending("tx-blocked", 50000, "acct-999", "slab-01", "0-1-2", "auth0|owner");
  t.verifyPhysicalApproval(Instant.now());
  t.recordGeminiDecision(AuditEvent.Reason.GEMINI_UNAVAILABLE, null,
   "Gemini could not assess this transfer, so banking was blocked.", Instant.now());
  var e = LiveTransferEventPublisher.envelope(t, Stage.GEMINI, Outcome.REJECTED,
   new SafePayload("GEMINI_UNAVAILABLE", ErrorState.BLOCKED));
  assertEquals(Transaction.Status.VERIFIED, e.status());
  assertEquals(ErrorState.BLOCKED, e.payload().errorState());
  assertEquals("GEMINI_UNAVAILABLE", e.payload().reasonCode());
  assertNull(e.payload().receipt());
  assertNull(e.payload().riskScore());
  assertNull(e.payload().lore());
 }

 @Test void freeTextReasonCodesAreRefused() {
  var publisher = new LiveTransferEventPublisher(null, null, json);
  assertThrows(IllegalArgumentException.class, () -> publisher.publish("tx", Stage.GEMINI, Outcome.REJECTED,
   new SafePayload("Gemini said: transfer looks bad", ErrorState.BLOCKED)));
 }
}
