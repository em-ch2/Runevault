package com.runevault.banking;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.mockito.ArgumentCaptor;
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

 private String body(HttpRequest request) throws Exception {
  var result = new CompletableFuture<String>();
  var bytes = new ByteArrayOutputStream();
  request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
   public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
   public void onNext(ByteBuffer item) { byte[] chunk = new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk); }
   public void onError(Throwable error) { result.completeExceptionally(error); }
   public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
  });
  return result.get();
 }

 @Test void accountLookupDistinguishesUnknownFromUnavailable() throws Exception {
  HttpClient found = mock(HttpClient.class);
  var foundResponse = response(200, "{\"_id\":\"" + DEST + "\",\"balance\":500}");
  when(found.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(foundResponse);
  assertTrue(gateway(found).destinationExists(DEST));

  HttpClient missing = mock(HttpClient.class);
  var missingResponse = response(404, "{}");
  when(missing.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(missingResponse);
  assertFalse(gateway(missing).destinationExists(DEST));

  HttpClient unavailable = mock(HttpClient.class);
  when(unavailable.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenThrow(new IOException("offline"));
  assertThrows(BankReadGateway.Unavailable.class, () -> gateway(unavailable).destinationExists(DEST));
 }

 @Test void transferHistoryProducesBoundedRiskFeatures() throws Exception {
  HttpClient http = mock(HttpClient.class);
  String today = java.time.LocalDate.now().toString();
  var historyResponse = response(200, "[{\"amount\":12.34,\"transaction_date\":\"" + today
   + "\",\"status\":\"completed\"},{\"amount\":50,\"status\":\"failed\"}]");
  when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(historyResponse);
  var history = gateway(http).transferHistory(DEST);
  assertEquals(2, history.observedTransfers());
  assertEquals(1, history.recentThirtyDays());
  assertEquals(6234L, history.totalAmountMinor());
  assertEquals(5000L, history.largestAmountMinor());
  assertEquals(1, history.failedTransfers());
  ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
  verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
  assertEquals("/accounts/" + DEST + "/transfers", request.getValue().uri().getPath());
 }

 @Test void destinationWithNoTransfersYetHasAnEmptyHistory() throws Exception {
  // Nessie answers 404 "No transfers found for this account" for an account that has never sent a transfer.
  HttpClient none = mock(HttpClient.class);
  var notFound = response(404, "\"No transfers found for this account\"");
  when(none.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(notFound);
  assertEquals(BankReadGateway.TransferHistory.empty(), gateway(none).transferHistory(DEST));

  HttpClient down = mock(HttpClient.class);
  var serverError = response(503, "{}");
  when(down.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(serverError);
  assertThrows(BankReadGateway.Unavailable.class, () -> gateway(down).transferHistory(DEST));
 }

 @Test void providerTransferCarriesSelectedPayeeAndExactCents() throws Exception {
  HttpClient http = mock(HttpClient.class);
  var before = response(200, "{\"balance\":1000}");
  var created = response(201, "{\"objectCreated\":{\"_id\":\"receipt-1\",\"payee_id\":\"" + DEST + "\",\"status\":\"pending\"}}");
  var after = response(200, "{\"balance\":1000}");
  when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(before).thenReturn(created).thenReturn(after);
  assertEquals(BankReceipt.Outcome.ACCEPTED_PENDING,
   gateway(http).transfer("tx-1", 50001, "USD", DEST).outcome());
  ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
  verify(http, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
  var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body(requests.getAllValues().get(1)));
  // The provider rejects unknown fields with HTTP 400, so the payee travels in the description.
  assertFalse(json.has("payee_id"));
  assertFalse(json.has("medium"));
  assertEquals("runevault:tx-1 to " + DEST, json.path("description").asText());
  assertEquals("500.01", json.path("amount").decimalValue().toPlainString());
 }

 @Test void mismatchedProviderPayeeCannotBeMarkedSuccessful() throws Exception {
  HttpClient http = mock(HttpClient.class);
  var before = response(200, "{\"balance\":1000}");
  var created = response(201, "{\"objectCreated\":{\"_id\":\"receipt-1\",\"payee_id\":\"other\",\"status\":\"completed\"}}");
  when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(before).thenReturn(created);
  assertEquals(BankReceipt.Outcome.UNKNOWN, gateway(http).transfer("tx-1", 50000, "USD", DEST).outcome());
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
   // Preflight balance, transfer, then the post-transfer balance check.
   verify(http, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
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

 @Test void newBalanceIsRecordedOnlyWhenTheBankConfirmsTheExactDrop() throws Exception {
  // $1000.00 before; a $500.00 transfer confirmed by a $500.00 balance afterwards.
  var before = response(200, "{\"balance\":1000}");
  var created = response(201, "{\"objectCreated\":{\"_id\":\"receipt-2\",\"status\":\"pending\"}}");
  var after = response(200, "{\"balance\":500}");
  HttpClient confirmed = mock(HttpClient.class);
  when(confirmed.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(before).thenReturn(created).thenReturn(after);
  assertEquals(50000L, gateway(confirmed).transfer("tx-4", 50000, "USD", DEST).newBalanceMinor());

  // Pending transfers that have not reduced the balance yet are reported as unavailable.
  var created2 = response(201, "{\"objectCreated\":{\"_id\":\"receipt-3\",\"status\":\"pending\"}}");
  var same = response(200, "{\"balance\":1000}");
  HttpClient unchanged = mock(HttpClient.class);
  when(unchanged.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(before).thenReturn(created2).thenReturn(same);
  assertNull(gateway(unchanged).transfer("tx-5", 50000, "USD", DEST).newBalanceMinor());
 }

 @Test void balanceReadFailureNeverChangesTheBankingOutcome() throws Exception {
  var balance = response(200, "{\"balance\":1000}");
  var completed = response(201, "{\"objectCreated\":{\"_id\":\"receipt-4\",\"status\":\"completed\"}}");
  HttpClient http = mock(HttpClient.class);
  when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(balance).thenReturn(completed)
   .thenThrow(new HttpTimeoutException("balance timeout"));
  var receipt = gateway(http).transfer("tx-6", 50000, "USD", DEST);
  assertEquals(BankReceipt.Outcome.COMPLETED, receipt.outcome());
  assertEquals("receipt-4", receipt.receiptId());
  assertNull(receipt.newBalanceMinor());
 }

 @Test void lookupReadsExistingReceiptAndRequiresMatchingAccountsAndAmount() throws Exception {
  var prior = new BankReceipt("receipt-1", "tx-lookup", "nessie", "pending", 50000, "USD", DEST,
   BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, java.time.Instant.now(), null);
  HttpClient matching = mock(HttpClient.class);
  var matchingResponse = response(200,
   "{\"_id\":\"receipt-1\",\"payer_id\":\"" + SOURCE + "\",\"payee_id\":\"" + DEST
    + "\",\"amount\":500,\"status\":\"completed\"}");
  var settledBalance = response(200, "{\"balance\":500}");
  when(matching.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
   .thenReturn(matchingResponse).thenReturn(settledBalance);
  BankReceipt completed = gateway(matching).lookup(prior).orElseThrow();
  assertEquals(BankReceipt.Outcome.COMPLETED, completed.outcome());
  // A completed transfer carries the source account's balance at settlement.
  assertEquals(50000L, completed.newBalanceMinor());
  ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
  verify(matching, org.mockito.Mockito.times(2)).send(request.capture(), any(HttpResponse.BodyHandler.class));
  assertEquals("GET", request.getAllValues().get(0).method());
  assertTrue(request.getAllValues().get(0).uri().getPath().endsWith("/transfers/receipt-1"));
  assertTrue(request.getAllValues().get(1).uri().getPath().endsWith("/accounts/" + SOURCE));

  // Still pending: no balance read, no balance reported.
  HttpClient pending = mock(HttpClient.class);
  var pendingResponse = response(200,
   "{\"_id\":\"receipt-1\",\"payer_id\":\"" + SOURCE + "\",\"payee_id\":\"" + DEST
    + "\",\"amount\":500,\"status\":\"pending\"}");
  when(pending.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pendingResponse);
  assertNull(gateway(pending).lookup(prior).orElseThrow().newBalanceMinor());
  verify(pending, org.mockito.Mockito.times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

  HttpClient mismatched = mock(HttpClient.class);
  var mismatchedResponse = response(200,
   "{\"_id\":\"receipt-1\",\"payer_id\":\"" + SOURCE + "\",\"payee_id\":\"other\",\"amount\":500,\"status\":\"completed\"}");
  when(mismatched.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mismatchedResponse);
  assertTrue(gateway(mismatched).lookup(prior).isEmpty());
 }

 @Test void lookupMatchesProviderTransfersWithoutAccountFieldsByDescription() throws Exception {
  var prior = new BankReceipt("receipt-1", "tx-lookup", "nessie", "pending", 50000, "USD", DEST,
   BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, java.time.Instant.now(), null);
  HttpClient matching = mock(HttpClient.class);
  var matchingResponse = response(200,
   "{\"id\":\"receipt-1\",\"amount\":500,\"status\":\"completed\",\"description\":\"runevault:tx-lookup to " + DEST + "\"}");
  when(matching.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(matchingResponse);
  assertEquals(BankReceipt.Outcome.COMPLETED, gateway(matching).lookup(prior).orElseThrow().outcome());

  HttpClient otherTransfer = mock(HttpClient.class);
  var otherResponse = response(200,
   "{\"id\":\"receipt-1\",\"amount\":500,\"status\":\"completed\",\"description\":\"runevault:tx-other to " + DEST + "\"}");
  when(otherTransfer.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(otherResponse);
  assertTrue(gateway(otherTransfer).lookup(prior).isEmpty());
 }
}
