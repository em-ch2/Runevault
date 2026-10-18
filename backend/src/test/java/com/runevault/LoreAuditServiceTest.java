package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.banking.BankReadGateway;
import com.runevault.lore.GeminiClient;
import com.runevault.lore.LoreAuditService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoreAuditServiceTest {
 private final GeminiClient client = mock(GeminiClient.class);
 private final BankReadGateway bankReads = mock(BankReadGateway.class);
 private final TransactionRepository transactions = mock(TransactionRepository.class);
 private final LoreAuditService lore = new LoreAuditService(client, new ObjectMapper(), bankReads, transactions);
 private final Transaction transaction = new Transaction("tx-1", 50000, "acct-999");

 private static final String VALID = """
  {"lore_translation":"The forge is lit to begin the great exchange.","translation_confidence":0.9,
   "risk_score":10,"risk_flags":["known destination"],"risk_explanation":"Matches earlier transfers.",
   "security_message":"This transfer looks routine."}
  """;

 private void answer(String json) {
  when(client.isConfigured()).thenReturn(true);
  when(bankReads.transferHistory(anyString())).thenReturn(BankReadGateway.TransferHistory.empty());
  when(bankReads.verifiedSourceBalanceMinor()).thenReturn(200000L);
  when(client.generateJson(anyString(), anyString(), any())).thenReturn(json);
 }

 private void assertInvalid(String json) {
  answer(json);
  assertThrows(LoreAuditService.InvalidResponse.class,
   () -> lore.audit(transaction, LoreAuditService.DICTIONARY));
 }

 @SuppressWarnings("unchecked")
 @Test void validAnswerPassesAndRequestsTheResponseSchema() throws Exception {
  answer(VALID);
  when(bankReads.transferHistory("acct-999"))
   .thenReturn(new BankReadGateway.TransferHistory(4, 2, 150000, 90000, 1, false));
  when(transactions.countByDestinationAndStatusIn(eq("acct-999"), any())).thenReturn(4L);
  when(transactions.averageAmountByDestinationAndStatusIn(eq("acct-999"), any())).thenReturn(25000.0);
  when(transactions.countByCreatedAtAfterAndCreatedAtBeforeAndTransactionIdNot(any(), any(), eq("tx-1")))
   .thenReturn(3L);
  var result = lore.audit(transaction, LoreAuditService.DICTIONARY);
  assertEquals(10, result.path("risk_score").asInt());
  ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
  ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
  verify(client).generateJson(anyString(), prompt.capture(), schema.capture());
  assertTrue(prompt.getValue().contains("transfersInLastThirtyDays: 2"));
  assertTrue(prompt.getValue().contains("largestHistoricalAmountMinor: 90000"));
  assertTrue(prompt.getValue().contains("recipientPaidBefore: true"));
  assertTrue(prompt.getValue().contains("amountToPriorRecipientAverageRatio: 2.00"));
  assertTrue(prompt.getValue().contains("transferRequestsInLastHour: 3"));
  assertTrue(prompt.getValue().contains("amountPercentOfCurrentSourceBalance: 25.00"));
  assertTrue(prompt.getValue().matches("(?s).*transferHourUtc: ([0-9]|1[0-9]|2[0-3])\\n.*"));
  assertEquals(java.util.List.of("lore_translation", "translation_confidence", "risk_score",
   "risk_flags", "risk_explanation", "security_message"), schema.getValue().get("required"));
 }

 @Test void unavailableHistoryDoesNotCallGemini() {
  answer(VALID);
  when(bankReads.transferHistory("acct-999")).thenThrow(new BankReadGateway.Unavailable());
  assertThrows(BankReadGateway.Unavailable.class,
   () -> lore.audit(transaction, LoreAuditService.DICTIONARY));
  verify(client, never()).generateJson(anyString(), anyString(), any());
 }

 @Test void malformedJsonAndMissingFieldsFailClosed() {
  answer("not JSON");
  assertThrows(Exception.class, () -> lore.audit(transaction, LoreAuditService.DICTIONARY));
  assertInvalid("""
   {"lore_translation":"A","translation_confidence":0.9,"risk_score":10}
   """);
  assertInvalid(VALID.replace("\"security_message\":\"This transfer looks routine.\"", "\"security_message\":\" \""));
 }

 @Test void outOfRangeOrWrongTypedNumbersFailClosed() {
  assertInvalid(VALID.replace("0.9", "1.1"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":101"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":10.5"));
  assertInvalid(VALID.replace("\"risk_score\":10", "\"risk_score\":\"10\""));
 }

 @Test void tooManyOrBlankFlagsFailClosed() {
  assertInvalid(VALID.replace("[\"known destination\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]"));
  assertInvalid(VALID.replace("[\"known destination\"]", "[\"\"]"));
 }

 @Test void unconfiguredGeminiFailsWithoutCallingIt() {
  when(client.isConfigured()).thenReturn(false);
  assertThrows(IllegalStateException.class,
   () -> lore.audit(transaction, LoreAuditService.DICTIONARY));
  verify(client, never()).generateJson(anyString(), anyString(), any());
 }
}
