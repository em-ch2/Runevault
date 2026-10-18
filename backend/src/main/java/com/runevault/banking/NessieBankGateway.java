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
import java.util.Optional;

@Component
@Profile("!test & !simulator")
public class NessieBankGateway implements BankGateway, BankReadGateway {

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
    public boolean destinationExists(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches() || sourceAccountId.equals(accountId))
            return false;
        try {
            JsonNode account = unwrap(get("/accounts/" + accountId));
            JsonNode id = account.hasNonNull("_id") ? account.get("_id") : account.path("id");
            if (!id.isTextual() || !accountId.equals(id.asText())) throw new Unavailable();
            return true;
        } catch (NessieException e) {
            if (e.status == 404) return false;
            throw new Unavailable();
        } catch (IOException e) {
            throw new Unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (IllegalArgumentException e) {
            throw new Unavailable();
        }
    }

    @Override
    public TransferHistory transferHistory(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) throw new Unavailable();
        try {
            JsonNode response = get("/accounts/" + accountId + "/transfers");
            if (response == null) throw new Unavailable();
            JsonNode transfers = response.isArray() ? response : response.path("transfers");
            if (!transfers.isArray()) throw new Unavailable();
            int observed = Math.min(transfers.size(), 1000);
            int recent = 0;
            int failed = 0;
            long total = 0;
            long largest = 0;
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.minusDays(30);
            for (int i = 0; i < observed; i++) {
                JsonNode transfer = transfers.get(i);
                if (!transfer.isObject() || !transfer.path("amount").isNumber()) throw new Unavailable();
                long amount = transfer.path("amount").decimalValue().movePointRight(2).longValueExact();
                if (amount < 0) throw new Unavailable();
                total = Math.addExact(total, amount);
                largest = Math.max(largest, amount);
                String date = transfer.path("transaction_date").asText("");
                if (!date.isBlank()) {
                    LocalDate transferDate = LocalDate.parse(date);
                    if (!transferDate.isBefore(cutoff) && !transferDate.isAfter(today)) recent++;
                }
                String status = transfer.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
                if (status.equals("failed") || status.equals("rejected") || status.equals("declined")) failed++;
            }
            return new TransferHistory(observed, recent, total, largest, failed, transfers.size() > observed);
        } catch (IOException | NessieException | IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            throw new Unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        }
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
        if (sourceAccountId.equals(payeeId)) {
            return result(transactionId, amountMinor, currency, destination, null,
                    "SAME_ACCOUNT", BankReceipt.Outcome.FAILED,
                    BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
        }

        BigDecimal amount = BigDecimal.valueOf(amountMinor, 2); // cents -> dollars

        ObjectNode body;
        BigDecimal balanceBefore;
        try {
            JsonNode source = unwrap(get("/accounts/" + sourceAccountId));
            if (!source.path("balance").isNumber()) throw new IllegalArgumentException("Missing balance");
            BigDecimal balance = source.path("balance").decimalValue();
            balanceBefore = balance;
            if (balance.compareTo(amount) < 0) {
                return result(transactionId, amountMinor, currency, destination, null,
                        "INSUFFICIENT_FUNDS", BankReceipt.Outcome.FAILED,
                        BankReceipt.Reason.PRE_SUBMISSION_FAILURE, null);
            }

            // prod-api.nessieisreal.com rejects extra fields ("medium", "payee_id") with HTTP 400,
            // so the payee is carried in the description instead.
            body = mapper.createObjectNode();
            body.put("status", "pending");
            if (amount.stripTrailingZeros().scale() <= 0) {
                body.put("amount", amount.longValueExact()); // whole dollars as an integer
            } else {
                body.put("amount", amount);
            }
            body.put("transaction_date", LocalDate.now().toString());
            body.put("description", description(transactionId, payeeId));

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
            if (created.hasNonNull("payee_id") && !payeeId.equals(created.path("payee_id").asText()))
                return unknown(transactionId, amountMinor, currency, destination,
                        "PAYEE_MISMATCH", BankReceipt.Reason.MALFORMED_RESPONSE, submittedAt);
            String receiptId = transferId(created);
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
                        BankReceipt.Outcome.ACCEPTED_PENDING, BankReceipt.Reason.ACCEPTED_PENDING, submittedAt)
                        .withNewBalanceMinor(confirmedBalance(transactionId, balanceBefore, amount));
            if (normalized.equals("completed") || normalized.equals("complete"))
                return result(transactionId, amountMinor, currency, destination, receiptId, status,
                        BankReceipt.Outcome.COMPLETED, BankReceipt.Reason.CONFIRMED_COMPLETED, submittedAt)
                        .withNewBalanceMinor(confirmedBalance(transactionId, balanceBefore, amount));
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

    @Override
    public Optional<BankReceipt> lookup(BankReceipt previous) {
        if (previous == null || !"nessie".equals(previous.provider())
                || previous.receiptId() == null || !previous.receiptId().matches("[A-Za-z0-9_-]{1,64}"))
            return Optional.empty();
        try {
            JsonNode detail = get("/transfers/" + previous.receiptId());
            // Classic Nessie returns payer/payee ids; this provider returns neither, only our description.
            boolean accountsMatch = detail.has("payer_id") || detail.has("payee_id")
                    ? sourceAccountId.equals(detail.path("payer_id").asText())
                        && previous.destination().equals(detail.path("payee_id").asText())
                    : description(previous.transactionId(), previous.destination())
                        .equals(detail.path("description").asText());
            if (!detail.isObject() || !previous.receiptId().equals(transferId(detail)) || !accountsMatch
                    || !detail.path("amount").isNumber()
                    || detail.path("amount").decimalValue().compareTo(BigDecimal.valueOf(previous.amountMinor(), 2)) != 0)
                return Optional.empty();
            String status = detail.path("status").asText("");
            if (!status.matches("[A-Za-z0-9_-]{1,64}")) return Optional.empty();
            BankReceipt.Outcome outcome;
            BankReceipt.Reason reason;
            switch (status.toLowerCase(java.util.Locale.ROOT)) {
                case "pending", "processing", "accepted" -> {
                    outcome = BankReceipt.Outcome.ACCEPTED_PENDING;
                    reason = BankReceipt.Reason.ACCEPTED_PENDING;
                }
                case "completed", "complete" -> {
                    outcome = BankReceipt.Outcome.COMPLETED;
                    reason = BankReceipt.Reason.CONFIRMED_COMPLETED;
                }
                case "rejected", "failed", "declined" -> {
                    outcome = BankReceipt.Outcome.FAILED;
                    reason = BankReceipt.Reason.DEFINITE_REJECTION;
                }
                default -> {
                    outcome = BankReceipt.Outcome.UNKNOWN;
                    reason = BankReceipt.Reason.PROVIDER_STATUS_UNKNOWN;
                }
            }
            // Once Nessie reports the transfer complete, the source balance reflects it.
            Long balance = outcome == BankReceipt.Outcome.COMPLETED ? settledBalance(previous.transactionId()) : null;
            return Optional.of(new BankReceipt(previous.receiptId(), previous.transactionId(), "nessie",
                    status, previous.amountMinor(), previous.currency(), previous.destination(), outcome,
                    reason, previous.submittedAt(), balance));
        } catch (IOException | RuntimeException e) {
            log.warn("Nessie receipt lookup unavailable for {} ({})", previous.transactionId(), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private BankReceipt unknown(String txId, long amountMinor, String currency, String destination,
            String status, BankReceipt.Reason reason, java.time.Instant submittedAt) {
        return result(txId, amountMinor, currency, destination, null, status,
                BankReceipt.Outcome.UNKNOWN, reason, submittedAt);
    }

    // Source balance after a transfer Nessie reports as completed; null when it cannot be read.
    // Other transfers from the same account may have settled too, so this is the account's balance
    // at settlement, not a figure derived from this transfer alone.
    private Long settledBalance(String transactionId) {
        try {
            JsonNode balance = unwrap(get("/accounts/" + sourceAccountId)).path("balance");
            return balance.isNumber() ? balance.decimalValue().movePointRight(2).longValueExact() : null;
        } catch (IOException | RuntimeException e) {
            log.warn("Balance after settlement of {} is unavailable ({})", transactionId, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // Reads the source balance after an accepted transfer. Returns it only when it is exactly the
    // pre-transfer balance minus this amount; any other value or error means "not reliably known".
    // The transfer was already submitted, so a failure here must never change the banking outcome.
    private Long confirmedBalance(String transactionId, BigDecimal before, BigDecimal amount) {
        try {
            JsonNode balance = unwrap(get("/accounts/" + sourceAccountId)).path("balance");
            if (!balance.isNumber()) return null;
            BigDecimal after = balance.decimalValue();
            if (after.compareTo(before.subtract(amount)) != 0) return null;
            return after.movePointRight(2).longValueExact();
        } catch (IOException | RuntimeException e) {
            log.warn("Balance after transfer {} is unavailable ({})", transactionId, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private BankReceipt result(String txId, long amountMinor, String currency, String destination,
            String receiptId, String status, BankReceipt.Outcome outcome, BankReceipt.Reason reason,
            java.time.Instant submittedAt) {
        return new BankReceipt(receiptId, txId, "nessie", status, amountMinor, currency,
                destination, outcome, reason, submittedAt, null);
    }

    // Create responses may be wrapped as {"message":..., "objectCreated":{...}}
    private JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Malformed Nessie response");
        return root.has("objectCreated") ? root.get("objectCreated") : root;
    }

    // Create responses carry "_id"; transfer lookups on prod-api.nessieisreal.com carry "id".
    private static String transferId(JsonNode transfer) {
        JsonNode id = transfer.hasNonNull("_id") ? transfer.get("_id") : transfer.path("id");
        return id.isTextual() ? id.asText() : null;
    }

    private static String description(String transactionId, String payeeId) {
        return "runevault:" + transactionId + " to " + payeeId;
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
