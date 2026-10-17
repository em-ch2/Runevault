# Execution handoff and dashboard events

## Internal physical-verification handoff (implemented)

`DeviceAuthorizationService.authorize` commits `VERIFIED`, `verifiedAt`, counter consumption, and the approval audit record before `DeviceApi` calls `TransactionProcessor.process(id)`. The processor reads the stored transaction; Gemini runs outside database locks. Only a persisted `GEMINI_APPROVED` decision permits `TransactionService.claimPicoAuthorized(id)` to lock the device and transaction, recheck policy and revocation, and atomically set `EXECUTING` and `executionClaimedAt` with an audit event. Its immutable `VerifiedTransaction` contains database execution details. `ExecutionService` submits to banking outside the claim transaction; `BankOutcomeService` persists the outcome and audit event together. An unknown bank outcome requires review and is never automatically resubmitted. This internal record is never a dashboard message.

## Dashboard event contract (target; stage publishing pending Person C)

The implemented `/ws/transfers` transport accepts single-use tickets and sends a camelCase JSON envelope. `TransferEventPublisher.publish(transactionId, stage, outcome, safePayload)` is the internal handoff for Person C. It is best-effort notification **after** the underlying database transaction commits; the authenticated transaction API remains the source of truth and Angular continues polling. The publisher currently has no pipeline call sites, so the stages below describe the agreed message shape, not events already emitted by the processing pipeline.

One JSON object is sent per stage. The existing envelope fields stay compatible; `status` and additional safe payload fields require coordinated backend publisher and Angular parser changes:

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
| `eventId`, `transactionId`, `stage`, `outcome`, `timestamp` | Notification UUID, stored transaction ID, publishing stage/result, UTC send time. `eventId` is not a durable database event ID. | Current WebSocket envelope. |
| `status` | Persisted transaction status, not inferred success. | Stored in `Transaction`; adding to the wire envelope is pending C. |
| `payload.translationConfidence`, `payload.riskScore` | Validated Gemini values (0–1 and 0–100); null if unavailable. | Stored by the pipeline; current `SafePayload` can carry them. Stage wiring is pending C. |
| `payload.lore`, `payload.riskFlags`, `payload.securityMessage` | Display-safe lore text, bounded risk flags, and safe user-facing message; null/empty when unavailable. Do not expose raw model output. | Not in the current publisher/transaction API. Gemini schema, safe persistence, redaction, and parser support are pending C. |
| `payload.reasonCode` | Fixed server reason from audit/Gemini/banking enums; null when none. Never raw exception text. | Fixed reasons exist; current wire field is `securityCode`. Mapping and stage wiring are pending C. |
| `payload.receipt` | Persisted provider, receipt ID if present, provider status, and submission time. A receipt ID alone does not mean completion. | Persisted bank fields exist; current wire exposes only `receiptId` and `providerStatus`. Structured receipt is pending C. |
| `payload.newBalanceMinor` | Source-account balance in integer cents, or null until reliably obtained from banking. Never calculate it from a receipt. | Not captured by the current Nessie adapter; pending C. |
| `payload.errorState` | Null or a fixed safe state such as `REJECTED`, `BLOCKED`, `FAILED`, `UNKNOWN`; accompany it with `reasonCode`. | Pending C. |

`stage` uses the existing publisher values `PENDING`, `VERIFIED`, `GEMINI`, `EXECUTING`, `BANK_PENDING`, `EXECUTED`, `FAILED`, `OUTCOME_UNKNOWN`. `outcome` uses `PENDING`, `APPROVED`, `REJECTED`, `ACCEPTED_PENDING`, `COMPLETED`, `FAILED`, `UNKNOWN`. `status` must use the persisted transaction enum, including historical `SIMULATED` where relevant. Rejections may leave a transaction `PENDING` or `VERIFIED`; do not synthesize a terminal database status. Gemini unavailability, malformed output, low confidence, or high risk blocks banking. `BANK_PENDING` means accepted but not confirmed; only `EXECUTED` means confirmed completion. `OUTCOME_UNKNOWN` must not be retried automatically.

Only publish fields derived from committed records and validated decisions. Never include bridge or Auth0 tokens, device/signing keys, signatures, counters, expected or entered glyph sequences, API keys, raw AI/bank responses, or exception text. Keep lore and messages bounded, plain text, and free of sensitive account data. Browser code must parse and display text safely. The existing mock-feed event types are separate from this live envelope; C must update the live parser and mock fixtures together when adding fields.

The intended Tiger Data `pipeline_events` destination is **not implemented**. Person C must define its durable schema, event ID/idempotency rules, and publisher independently of this in-memory socket transport. Do not claim socket delivery provides durable audit or metrics storage. Existing `AuditEvent` rows remain the durable security audit.

## Visibility and connection behavior (implemented)

The publisher reads the transaction and sends only to active sockets whose verified JWT `sub` equals the row's `requesterSubject`. Bridge-created transactions without a requester have no socket audience. `admin:platform` does not implicitly grant visibility to another user's events. Never take a recipient from caller payload.

The browser gets an Auth0 access token for `https://api.runevault` with `read:transactions`, POSTs `/api/ws-ticket` with `Authorization: Bearer <token>`, then connects to `/ws/transfers` using the single WebSocket subprotocol `runevault.ticket.<ticket>`. It never puts a reusable access token in the URL. The ticket is single-use and expires after at most 30 seconds or at JWT expiry; the socket closes at token expiry. Reconnect with a fresh ticket. Angular `FeedService` uses `environment.feedUrl` when configured; polling remains available.

Tickets and sessions are in memory on one backend instance. Restarts drop them; there is no replay, durable delivery, or cross-instance routing. Client-to-server messages cannot mutate or approve transactions. Keep proxy access logs from recording ticket subprotocols or sensitive query strings.
