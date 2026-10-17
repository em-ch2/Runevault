package com.runevault.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;

@Component
@Profile("!test & !simulator")
public class NessieBankGateway implements BankGateway {

    private static final Logger log = LoggerFactory.getLogger(NessieBankGateway.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String sourceAccountId;

    @org.springframework.beans.factory.annotation.Autowired
    public NessieBankGateway(
            @Value("${nessie.base-url}") String baseUrl,
            @Value("${nessie.api-key}") String apiKey,
            @Value("${nessie.source-account-id}") String sourceAccountId) {
        this(baseUrl, apiKey, sourceAccountId,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), false);
    }

    NessieBankGateway(String baseUrl, String apiKey, String sourceAccountId, HttpClient http, boolean localTest) {
        if (baseUrl == null || !(baseUrl.startsWith("https://")
                || (localTest && baseUrl.startsWith("http://127.0.0.1"))) || apiKey == null || apiKey.isBlank()
                || sourceAccountId == null || !ACCOUNT_ID.matcher(sourceAccountId).matches()) {
            throw new IllegalStateException("Configure Nessie HTTPS URL, API key, and source account ID");
        }
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.sourceAccountId = sourceAccountId;
    }

    @Override
    public BankReceipt transfer(String transactionId, long amountMinor, String currency, String destination) {
        if (!"USD".equals(currency)) {
            return result(transactionId, amountMinor, currency, destination, null,
                    "UNSUPPORTED_CURRENCY", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        }

        // destination is the Nessie payee account id (UUID)
        String payeeId = destination;
        if (!ACCOUNT_ID.matcher(payeeId).matches()) {
            return result(transactionId, amountMinor, currency, destination, null,
                    "INVALID_DESTINATION", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        }

        BigDecimal amount = BigDecimal.valueOf(amountMinor, 2); // cents -> dollars

        ObjectNode body;
        try {
            JsonNode source = unwrap(get("/accounts/" + sourceAccountId));
            if (!source.path("balance").isNumber()) throw new IllegalArgumentException("Missing balance");
            BigDecimal balance = source.path("balance").decimalValue();
            if (balance.compareTo(amount) < 0) {
                return result(transactionId, amountMinor, currency, destination, null,
                        "INSUFFICIENT_FUNDS", BankReceipt.Outcome.FAILED,
                        BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
            }

            body = mapper.createObjectNode();
            body.put("status", "pending");
            if (amount.stripTrailingZeros().scale() <= 0) {
                body.put("amount", amount.longValueExact()); // whole dollars as an integer
            } else {
                body.put("amount", amount);
            }
            body.put("transaction_date", LocalDate.now().toString());
            // Nessie's create call has no payee field, so keep the trace in the description
            body.put("description", "runevault:" + transactionId + " to " + payeeId);

        } catch (NessieException e) {
            log.warn("Nessie preflight rejected transaction {} with HTTP {}", transactionId, e.status);
            return result(transactionId, amountMinor, currency, destination, null,
                    "PREFLIGHT_REJECTED", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        } catch (IOException e) {
            return result(transactionId, amountMinor, currency, destination, null,
                    "PREFLIGHT_UNAVAILABLE", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(transactionId, amountMinor, currency, destination, null,
                    "PREFLIGHT_INTERRUPTED", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        } catch (IllegalArgumentException e) {
            return result(transactionId, amountMinor, currency, destination, null,
                    "PREFLIGHT_MALFORMED", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        }

        java.time.Instant submittedAt = java.time.Instant.now();
        try {
            JsonNode created = unwrap(post("/accounts/" + sourceAccountId + "/transfers", body));
            if (created == null || !created.isObject()) return unknown(transactionId, amountMinor, currency,
                    destination, "MALFORMED_RESPONSE", BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            String receiptId = created.path("_id").isTextual() ? created.path("_id").asText() : null;
            if (receiptId != null && receiptId.isBlank()) receiptId = null;
            if (receiptId != null && (receiptId.length() > 64 || !receiptId.matches("[A-Za-z0-9_-]+")))
                return unknown(transactionId, amountMinor, currency, destination,
                        "INVALID_RECEIPT", BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            String status = created.path("status").isTextual() ? created.path("status").asText() : null;
            if (status == null || status.isBlank()) return result(transactionId, amountMinor, currency,
                    destination, receiptId, "MISSING_STATUS", BankReceipt.Outcome.UNKNOWN,
                    BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            if (status.length() > 64 || !status.matches("[A-Za-z0-9_-]+"))
                return result(transactionId, amountMinor, currency, destination, receiptId,
                        "INVALID_STATUS", BankReceipt.Outcome.UNKNOWN,
                        BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            String normalized = status.toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("rejected") || normalized.equals("failed") || normalized.equals("declined"))
                return result(transactionId, amountMinor, currency, destination, receiptId, status,
                        BankReceipt.Outcome.FAILED, BankReceipt.Reason.DEFINITE_REJECTION, submittedAt);
            if (receiptId == null) return unknown(transactionId, amountMinor, currency, destination,
                    "MISSING_RECEIPT", BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            if (normalized.equals("pending") || normalized.equals("processing") || normalized.equals("accepted"))
                return result(transactionId, amountMinor, currency, destination, receiptId, status,
                        BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, submittedAt);
            if (normalized.equals("completed") || normalized.equals("complete"))
                return result(transactionId, amountMinor, currency, destination, receiptId, status,
                        BankReceipt.Outcome.COMPLETED, BankReceipt.Reason.CONFIRMED_COMPLETED, submittedAt);
            return result(transactionId, amountMinor, currency, destination, receiptId, status,
                    BankReceipt.Outcome.UNKNOWN, BankReceipt.Reason.PROVIDER_STATUS_UNKNOWN, submittedAt);
        } catch (NessieException e) {
            if (e.status >= 400 && e.status < 500 && e.status != 408 && e.status != 429)
                return result(transactionId, amountMinor, currency, destination, null, "HTTP_" + e.status,
                        BankReceipt.Outcome.FAILED, BankReceipt.Reason.DEFINITE_REJECTION, submittedAt);
            return unknown(transactionId, amountMinor, currency, destination, "HTTP_" + e.status,
                    BankReceipt.Reason.SUBMISSION_UNCERTAIN, submittedAt);
        } catch (IOException e) {
            return unknown(transactionId, amountMinor, currency, destination, "NETWORK_UNCERTAIN",
                    BankReceipt.Reason.SUBMISSION_UNCERTAIN, submittedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unknown(transactionId, amountMinor, currency, destination, "INTERRUPTED",
                    BankReceipt.Reason.SUBMISSION_UNCERTAIN, submittedAt);
        }
    }

    private BankReceipt unknown(String txId, long amountMinor, String currency, String destination,
            String status, BankReceipt.Reason reason, java.time.Instant submittedAt) {
        return result(txId, amountMinor, currency, destination, null, status,
                BankReceipt.Outcome.UNKNOWN, reason, submittedAt);
    }

    private BankReceipt result(String txId, long amountMinor, String currency, String destination,
            String receiptId, String status, BankReceipt.Outcome outcome, BankReceipt.Reason reason,
            java.time.Instant submittedAt) {
        return new BankReceipt(receiptId, txId, "nessie", status, amountMinor, currency,
                destination, outcome, reason, submittedAt);
    }

    // Create responses may be wrapped as {"message":..., "objectCreated":{...}}
    private JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Malformed Nessie response");
        return root.has("objectCreated") ? root.get("objectCreated") : root;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(req);
    }

    private JsonNode post(String path, JsonNode body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return send(req);
    }

    private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new NessieException(resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
    }

    private static class NessieException extends RuntimeException {
        final int status;
        NessieException(int status) {
            super("Nessie returned HTTP " + status);
            this.status = status;
        }
    }
}
