# Verified transaction processing

Both the Pico authorization endpoint and legacy signed approvals use `TransactionProcessor`. Signing bytes, bridge authentication, counter replay protection, and wrong-glyph lockout remain unchanged. No public processing or human-approval endpoint exists.

Requests are saved as PENDING. Authorization commits VERIFIED before AI runs. For Pico, counter consumption, detail/glyph matching, and VERIFIED are one database transaction. For legacy approvals, the verified database save precedes in-memory challenge consumption. A failed save permits retry; a committed verification cannot be authorized twice.

The shared lifecycle is PENDING -> VERIFIED -> AUDITED -> APPROVED -> EXECUTING -> SIMULATED (fake) or EXECUTED (a future provider). HIGH risk becomes REJECTED. Review-required results become APPROVAL_PENDING and cannot execute. Known bank rejection becomes FAILED; ambiguous errors become EXECUTION_UNKNOWN. Pending requests expire after 120 seconds. Existing SIMULATED records are retained and are never automatically processed again.

Each phase uses a short database transaction with row locking. A durable audit claim prevents duplicate AI calls. Execution intent commits before calling BankGateway. No database transaction or lock spans an external AI or bank request. Concurrent processing can observe an intermediate status but cannot claim the same bank operation twice.

Persisted fields include the audit JSON/source, prompt and dictionary versions, review/block reason, confirmed bank receipt JSON, and verification/audit/execution/completion timestamps. `fake_bank_receipts` uses transaction ID as its primary key. The fake gateway locks the matching transaction row, validates details, and stores or reuses a receipt atomically, without an in-memory cache. Recreating the gateway or restarting with the same database retains its deduplication. Different details for an existing ID are rejected.

## Policy and configuration

- `RUNEVAULT_REVIEW_SCORE_THRESHOLD`: review at or above this score; default 0.5, range 0..1.
- `RUNEVAULT_MAX_AUTOMATIC_AMOUNT_MINOR`: largest automatically eligible amount; default 100000 USD cents. Larger amounts require review.
- HIGH risk always blocks. MEDIUM risk, requested review, unknown AI source, or AI fallback always require review regardless of score/amount.
- `RUNEVAULT_AI_PROVIDER`: `mock` (default, explicitly offline simulation) or `gemini`.
- Gemini reads `GEMINI_API_KEY`, `GEMINI_MODEL`, and `GEMINI_TIMEOUT_SECONDS` from the backend environment. Never commit keys. The prompt contains only amount, currency, destination, and decorative glyph lore; it does not contain device keys, counters, signatures, bridge tokens, or bank credentials.
- `RUNEVAULT_EXECUTION_STALE_SECONDS`: default 300. A periodic scan marks unconfirmed EXECUTING intents older than this as EXECUTION_UNKNOWN, including after restart. A subsequently confirmed in-flight receipt can still finalize the transaction. The scan never calls the bank.

Hardware glyphs follow the authoritative `0-2` dash-separated protocol (1..64 values). Legacy ABCD sequences remain supported. `lore-v2` contains fictional ancient meanings, preserves press order, and grants no authorization.

## Recovery and limits

APPROVAL_PENDING has no approval workflow yet. Do not manually turn it into APPROVED without an independently designed, authenticated review process. EXECUTION_UNKNOWN requires provider/receipt reconciliation, not blind retry. A process crash during auditing leaves a durable audit claim at VERIFIED; operator investigation is needed. Committed AUDITED/APPROVED states can be resumed internally, but there is no public recovery endpoint or automatic execution worker.

There is no exactly-once external execution claim: a provider may execute before a connection fails. The fake provider offers durable local idempotency; a future real provider needs its own idempotency and reconciliation contract. If the database is unavailable while recording a bank outcome, the intent remains EXECUTING until stale recovery can record UNKNOWN. PostgreSQL schema updates remain the existing Hibernate plus additive initialization approach, not a production migration framework. Existing rows and historical status strings are retained.

The next dashboard step is an authenticated, read-only transaction list/detail API exposing persisted status, analysis, review reason, and confirmed receipt. It should clearly distinguish pending review, rejected, unknown, and simulated results. It must not add an authorization bypass or a transfer/retry button.
