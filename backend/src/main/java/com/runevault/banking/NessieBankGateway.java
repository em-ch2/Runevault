package com.runevault.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "nessie.enabled", havingValue = "true")
public class NessieBankGateway implements BankGateway {

    private static final Logger log = LoggerFactory.getLogger(NessieBankGateway.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9a-f]{24}");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String sourceAccountId;

    public NessieBankGateway(
            @Value("${nessie.base-url}") String baseUrl,
            @Value("${nessie.api-key}") String apiKey,
            @Value("${nessie.source-account-id}") String sourceAccountId) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.sourceAccountId = sourceAccountId;
    }

    @Override
    public BankReceipt transfer(String transactionId, long amountMinor, String currency, String destination) {
        if (!"USD".equals(currency)) {
            return failed(transactionId, amountMinor, currency, destination, "FAILED_UNSUPPORTED_CURRENCY");
        }

        // destination is the Nessie payee account id (24 hex chars)
        String payeeId = destination;
        if (!ACCOUNT_ID.matcher(payeeId).matches()) {
            return failed(transactionId, amountMinor, currency, destination, "FAILED_UNKNOWN_DESTINATION");
        }

        BigDecimal amount = BigDecimal.valueOf(amountMinor, 2); // cents -> dollars

        try {
            JsonNode source = unwrap(get("/accounts/" + sourceAccountId));
            BigDecimal balance = source.path("balance").decimalValue();
            if (balance.compareTo(amount) < 0) {
                return failed(transactionId, amountMinor, currency, destination, "FAILED_INSUFFICIENT_FUNDS");
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("medium", "balance");
            body.put("payee_id", payeeId);
            body.put("amount", amount);
            body.put("transaction_date", LocalDate.now().toString());
            body.put("description", "runevault:" + transactionId); // traces back to our record

            JsonNode created = unwrap(post("/accounts/" + sourceAccountId + "/transfers", body));
            String receiptId = created.path("_id").asText(null);
            if (receiptId == null) {
                log.error("Nessie transfer response had no _id for transaction {}", transactionId);
                return failed(transactionId, amountMinor, currency, destination, "FAILED_BANK_ERROR");
            }

            return new BankReceipt(
                    receiptId,
                    transactionId,
                    "nessie",
                    created.path("status").asText("pending"),
                    amountMinor,
                    currency,
                    destination);
        } catch (NessieException e) {
            log.error("Nessie rejected transfer for transaction {}: HTTP {}", transactionId, e.status);
            return failed(transactionId, amountMinor, currency, destination, "FAILED_BANK_ERROR");
        } catch (IOException e) {
            log.error("Nessie unreachable for transaction {}", transactionId, e);
            return failed(transactionId, amountMinor, currency, destination, "FAILED_BANK_UNAVAILABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(transactionId, amountMinor, currency, destination, "FAILED_BANK_UNAVAILABLE");
        }
    }

    private BankReceipt failed(String txId, long amountMinor, String currency, String destination, String status) {
        return new BankReceipt(null, txId, "nessie", status, amountMinor, currency, destination);
    }

    // Create responses may be wrapped as {"message":..., "objectCreated":{...}}
    private JsonNode unwrap(JsonNode root) {
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