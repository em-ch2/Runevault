package com.runevault.banking;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NessieBankGatewayOutcomeTest {
 private static final String SOURCE = "2c16f950-7683-4239-8a46-7b86c84e2128";
 private static final String DEST = "5a1b0e4e-4f52-4360-9000-000000000002";

 @SuppressWarnings("unchecked")
 private HttpResponse<String> response(int status, String body) {
  HttpResponse<String> response = mock(HttpResponse.class);
  when(response.statusCode()).thenReturn(status);
  when(response.body()).thenReturn(body);
  return response;
 }

 private NessieBankGateway gateway(HttpClient http) {
  return new NessieBankGateway("http://127.0.0.1:9999", "test-key", SOURCE, http, true);
 }

 @Test void missingNormalBankConfigurationFailsClearly() {
  assertThrows(IllegalStateException.class,
   () -> new NessieBankGateway("https://prod-api.nessieisreal.com", "", SOURCE));
  assertThrows(IllegalStateException.class,
   () -> new NessieBankGateway("https://prod-api.nessieisreal.com", "test-key", ""));
 }

 @Test void pendingReceiptIsNotCompletionAndConfirmedStatusIs() throws Exception {
  for (String status : new String[] {"pending", "completed"}) {
   HttpClient http = mock(HttpClient.class);
   var balance = response(200, "{\"balance\":1000}");
   var created = response(201, "{\"objectCreated\":{\"_id\":\"receipt-1\",\"status\":\"" + status + "\"}}");
   when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
    .thenReturn(balance).thenReturn(created);
   var receipt = gateway(http).transfer("tx-1", 50000, "USD", DEST);
   assertEquals(status.equals("pending") ? BankReceipt.Outcome.ACCEPTED_PENDING
    : BankReceipt.Outcome.COMPLETED, receipt.outcome());
   assertEquals("receipt-1", receipt.receiptId());
   assertNotNull(receipt.submittedAt());
   verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }
 }

 @Test void definiteRejectionAndUnknownAfterSubmissionStayDistinct() throws Exception {
  HttpClient rejected = mock(HttpClient.class);
  var balance = response(200, "{\"balance\":1000}");
  var badRequest = response(400, "{}");
  when(rejected.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(balance).thenReturn(badRequest);
  var failed = gateway(rejected).transfer("tx-2", 50000, "USD", DEST);
  assertEquals(BankReceipt.Outcome.FAILED, failed.outcome());
  assertEquals(BankReceipt.Reason.DEFINITE_REJECTION, failed.reason());

  HttpClient timedOut = mock(HttpClient.class);
  when(timedOut.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(balance)
   .thenThrow(new HttpTimeoutException("timeout"));
  var unknown = gateway(timedOut).transfer("tx-3", 50000, "USD", DEST);
  assertEquals(BankReceipt.Outcome.UNKNOWN, unknown.outcome());
  assertEquals(BankReceipt.Reason.SUBMISSION_UNCERTAIN, unknown.reason());
  assertNotNull(unknown.submittedAt());
 }

 @Test void malformedPostResponseCannotBecomeSuccess() throws Exception {
  for (String body : new String[] {"{}", "{\"objectCreated\":{\"_id\":\"receipt-1\"}}", "not-json"}) {
   HttpClient http = mock(HttpClient.class);
   var balance = response(200, "{\"balance\":1000}");
   var malformed = response(201, body);
   when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
    .thenReturn(balance).thenReturn(malformed);
   assertEquals(BankReceipt.Outcome.UNKNOWN, gateway(http).transfer("tx-4", 50000, "USD", DEST).outcome());
  }
 }

 @Test void failureBeforePostIsDefiniteAndNoTransferIsSent() throws Exception {
  HttpClient http = mock(HttpClient.class);
  when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenThrow(new IOException("offline"));
  var receipt = gateway(http).transfer("tx-5", 50000, "USD", DEST);
  assertEquals(BankReceipt.Outcome.FAILED, receipt.outcome());
  assertEquals(BankReceipt.Reason.PRE_SUBMISSION_FAILURE, receipt.reason());
  assertNull(receipt.submittedAt());
  verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
 }
}
