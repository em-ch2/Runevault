# Execution handoff and dashboard events

## Internal physical-verification handoff (implemented)

`DeviceAuthorizationService.authorize` commits `VERIFIED`, `verifiedAt`, counter consumption, and the approval audit record before `DeviceApi` calls `TransactionProcessor.process(id)`. The processor reads the stored transaction; Gemini runs outside database locks. Only a persisted `GEMINI_APPROVED` decision permits `TransactionService.claimPicoAuthorized(id)` to lock the device and transaction, recheck policy and revocation, and atomically set `EXECUTING` and `executionClaimedAt` with an audit event. Its immutable `VerifiedTransaction` contains database execution details. `ExecutionService` submits to banking outside the claim transaction; `BankOutcomeService` persists the outcome and audit event together. An unknown bank outcome requires review and is never automatically resubmitted. This internal record is never a dashboard message.

## Dashboard event contract (implemented)

The `/ws/transfers` transport accepts single-use tickets and sends a camelCase JSON envelope. Every committed pipeline stage is reported through `PipelineEvents` (`backend/.../events/PipelineEvents.java`), which first writes a durable `pipeline_events` row and then calls `TransferEventPublisher.publish(transactionId, stage, outcome, safePayload)`. Both run only **after** the stage's database transaction commits, and each is independent and best-effort: a failure is logged and never rolls back authorization, changes a banking outcome, or triggers another transfer. The authenticated transaction API remains the source of truth. Angular applies events live, re-fetches state after every (re)connect, and polls only while the socket is down.

Callers pass only fixed codes: `SafePayload(reasonCode, errorState)`. Every other payload field is read by the publisher from the committed transaction row, so no caller can inject text.

| Stage / outcome | Published after | `reasonCode` | `errorState` |
| --- | --- | --- | --- |
| `PENDING` / `PENDING` | `createPending` commits (bridge or dashboard) | — | — |
| `VERIFIED` / `APPROVED` | `DeviceAuthorizationService.authorize` commits | — | — |
| `VERIFIED` / `REJECTED` | A signed approval for an existing transaction is refused | latest `DEVICE_AUTHORIZATION` audit reason, e.g. `INVALID_SIGNATURE`, `COUNTER_REPLAY`, `INCORRECT_GLYPHS` | `REJECTED` |
| `GEMINI` / `APPROVED` or `REJECTED` | `GeminiDecisionService.record` commits | `GEMINI_APPROVED`, `GEMINI_HIGH_RISK`, `GEMINI_LOW_CONFIDENCE`, `GEMINI_INVALID_RESPONSE`, `GEMINI_UNAVAILABLE` | `BLOCKED` when not approved |
| `EXECUTING` / `APPROVED` | `claimPicoAuthorized` commits | — | — |
| `BANK_PENDING`, `EXECUTED`, `FAILED`, `OUTCOME_UNKNOWN` | `BankOutcomeService.record` commits | `BankReceipt.Reason` | `FAILED` / `UNKNOWN` for the last two |

Refused approvals for transaction IDs that do not exist are not reported.

One JSON object is sent per stage:

```json
{
  "eventId": "new UUID for this notification",
  "transactionId": "database transaction ID",
  "stage": "BANK_PENDING",
  "outcome": "ACCEPTED_PENDING",
  "status": "BANK_PENDING",
  "timestamp": "2026-10-17T12:00:00Z",
  "payload": {
    "lore": null,
    "translationConfidence": null,
    "riskScore": null,
    "riskFlags": [],
    "securityMessage": null,
    "explanation": "Risk score 15 is within the limit of 30 and translation confidence 1 meets the minimum of 0.8, so the transfer may proceed.",
    "reasonCode": "ACCEPTED_PENDING",
    "receipt": {
      "provider": "nessie",
      "receiptId": "provider receipt ID",
      "providerStatus": "pending",
      "submittedAt": "2026-10-17T11:59:59Z"
    },
    "newBalanceMinor": null,
    "errorState": null
  }
}
```

| Field | Meaning and source | Availability |
| --- | --- | --- |
| `eventId`, `transactionId`, `stage`, `outcome`, `timestamp` | Notification UUID, stored transaction ID, publishing stage/result, UTC send time. `eventId` is not the durable `pipeline_events` ID. | Implemented. |
| `status` | Persisted transaction status, not inferred success. | Implemented. |
| `payload.translationConfidence`, `payload.riskScore` | Validated Gemini values (0–1 and 0–100) read from the stored assessment; null if unavailable. | Implemented. |
| `payload.lore`, `payload.riskFlags`, `payload.securityMessage` | Gemini's six-field answer (`lore-risk-v2`, enforced by a Gemini `responseSchema` and re-validated) passed through `SafeAssessment`: plain text, capped at 2000 / 5×80 / 300 characters, and the approval glyph sequence (or anything shaped like one) replaced with `[sealed glyphs]`. The dashboard API uses the same filter. | Implemented. |
| `payload.explanation` | Backend-written, plain-English reason for the Gemini decision, persisted as `geminiDecisionExplanation` (max 300). Built from fixed templates and validated numbers, never from model text. | Implemented (added to the contract). |
| `payload.reasonCode` | Fixed server reason from audit/Gemini/banking enums; null when none. Never raw exception text. Free-text codes are refused by the publisher. | Implemented (replaces `securityCode`). |
| `payload.receipt` | Persisted provider, receipt ID if present, provider status, and submission time. Null before banking. A receipt ID alone does not mean completion. | Implemented. |
| `payload.newBalanceMinor` | Source-account balance in integer cents, set only when Nessie's post-transfer balance equals the pre-transfer balance minus this amount exactly; otherwise null and shown as "Unavailable". Never calculated from a receipt. A failed balance read never changes the bank outcome. | Implemented. The fake bank keeps no ledger, so it is always null offline. |
| `payload.errorState` | Null or `REJECTED`, `BLOCKED`, `FAILED`, `UNKNOWN`, always with `reasonCode`. | Implemented. |

`stage` uses the existing publisher values `PENDING`, `VERIFIED`, `GEMINI`, `EXECUTING`, `BANK_PENDING`, `EXECUTED`, `FAILED`, `OUTCOME_UNKNOWN`. `outcome` uses `PENDING`, `APPROVED`, `REJECTED`, `ACCEPTED_PENDING`, `COMPLETED`, `FAILED`, `UNKNOWN`. `status` must use the persisted transaction enum, including historical `SIMULATED` where relevant. Rejections may leave a transaction `PENDING` or `VERIFIED`; do not synthesize a terminal database status. Gemini unavailability, malformed output, low confidence, or high risk blocks banking. `BANK_PENDING` means accepted but not confirmed; only `EXECUTED` means confirmed completion. `OUTCOME_UNKNOWN` must not be retried automatically.

Only publish fields derived from committed records and validated decisions. Never include bridge or Auth0 tokens, device/signing keys, signatures, counters, expected or entered glyph sequences, API keys, raw AI/bank responses, or exception text. Keep lore and messages bounded, plain text, and free of sensitive account data. Browser code must parse and display text safely. The existing mock-feed event types are separate from this live envelope; C must update the live parser and mock fixtures together when adding fields.

## Tiger Data `pipeline_events` (implemented)

`PipelineEventWriter` stores one append-only row per committed stage, separately from socket delivery (socket delivery is not durable). Existing `AuditEvent` rows remain the security audit; `pipeline_events` is for traces and metrics.

| Column | Meaning |
| --- | --- |
| `event_id` | UUID. Idempotency: a once-per-transfer stage uses a name-based UUID of `transactionId|stage|outcome`, so re-reporting it inserts nothing. Each refused approval attempt (`VERIFIED`/`REJECTED`) gets a random UUID, so every attempt is kept. |
| `occurred_at` | When the stage was reported (UTC). Hypertable time column; primary key is `(occurred_at, event_id)`. |
| `transaction_id`, `stage`, `outcome`, `status` | Transaction, publisher stage/outcome, and persisted status at that moment. |
| `reason_code` | Same fixed code as the event's `reasonCode`. |
| `risk_score`, `translation_confidence` | Validated Gemini numbers once audited, else null. |
| `latency_ms` | Milliseconds from transaction creation to this stage. |

On TimescaleDB (local Compose image `timescale/timescaledb:2.17.2-pg17`, or Tiger Cloud) the writer makes the table a hypertable and creates the `pipeline_events_per_minute` continuous aggregate (`time_bucket('1 minute')` by stage and outcome, with event count and average latency; `materialized_only = false` so the current minute is included; refresh policy every minute). On plain PostgreSQL or H2 it stays an ordinary table.

**Schema ownership (coordinate with B):** these statements currently run idempotently at startup in `PipelineEventWriter.afterPropertiesSet`. If database bootstrap moves to migration files, move them there unchanged and remove that method.

`GET /api/metrics/pipeline?minutes=1..1440` (requires `read:transactions`, counted as a dashboard read) returns per-minute `approved` (Gemini approvals) and `blocked` (Gemini blocks plus refused tablet approvals), zero-filled, with totals, the data `source`, and the configured `policy` thresholds the dashboard gauges mark.

## Visibility and connection behavior (implemented)

The publisher reads the transaction and sends only to active sockets whose verified JWT `sub` equals the row's `requesterSubject`. Bridge-created transactions without a requester have no socket audience. `admin:platform` does not implicitly grant visibility to another user's events. Never take a recipient from caller payload.

The browser gets an Auth0 access token for `https://api.runevault` with `read:transactions`, POSTs `/api/ws-ticket` with `Authorization: Bearer <token>`, then connects to `/ws/transfers` using the single WebSocket subprotocol `runevault.ticket.<ticket>`. It never puts a reusable access token in the URL. The ticket is single-use and expires after at most 30 seconds or at JWT expiry; the socket closes at token expiry. Reconnect with a fresh ticket. Angular `FeedService` uses `environment.feedUrl` when configured; polling remains available.

Tickets and sessions are in memory on one backend instance. Restarts drop them; there is no replay, durable delivery, or cross-instance routing. Client-to-server messages cannot mutate or approve transactions. Keep proxy access logs from recording ticket subprotocols or sensitive query strings.
