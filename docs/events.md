# Execution handoff and event contracts

## Internal VerifiedTransaction handoff

`DeviceAuthorizationService.authorize` commits physical `VERIFIED`, `verifiedAt`, device-counter consumption, and the approval audit record before `DeviceApi` calls `TransactionProcessor.process(id)`. The processor receives only the transaction ID. It reads the stored glyphs, amount, destination, currency, and device. Gemini runs outside database transactions. Its validated output and fixed decision reason are then persisted with a `GEMINI_DECISION` audit event in a short transaction.

Only a `GEMINI_APPROVED` decision permits `TransactionService.claimPicoAuthorized(id)`. That service locks device then transaction, rechecks revocation, physical evidence, deterministic policy, and the stored Gemini approval, and atomically sets `EXECUTING`/`executionClaimedAt` with an `EXECUTION_CLAIM` audit event. It returns an immutable `VerifiedTransaction` containing the stored ID, device ID, amount in cents, currency, destination, glyphs, status, and timestamps. `ExecutionService` receives this claimed value and calls the bank outside the lock. `BankOutcomeService` saves the provider result and audit event atomically afterward. A claim prevents duplicate local execution attempts; it does not guarantee exactly-once behavior at Nessie. Unknown outcomes must be reviewed before any manual recovery.

## Dashboard and Tiger Data event contract

This internal Java record is **not** Person C's dashboard event contract. The current Angular dashboard reads the authenticated transaction API and polls for status changes. A future Tiger Data event stream needs a separately agreed schema, delivery mechanism, consumer identity, and idempotency key. The cloud package does not include a Tiger Data publisher. Physical verification, Gemini approval, execution claim, and bank outcome are distinct events and must remain distinguishable in any future stream. Do not publish a `VerifiedTransaction` as if it were a completed transfer.

## WebSocket notification handoff for Person C

`com.runevault.ws.TransferEventPublisher.publish(transactionId, stage, outcome, safePayload)` is the internal Java interface Person C should inject. Call it only **after** the corresponding database transaction has committed; it makes no banking or AI calls and does not change transaction state. It reads the transaction from PostgreSQL and sends only to active sockets whose JWT subject equals that row's `requesterSubject`. A bridge-created row with no requester has no WebSocket audience. `admin:platform` does not grant visibility to another user's events. Do not pass a recipient supplied by a caller. Keep polling as the source of truth.

Wire JSON envelope:

```json
{
  "eventId": "random UUID",
  "transactionId": "database transaction ID",
  "stage": "VERIFIED",
  "outcome": "APPROVED",
  "timestamp": "2026-10-17T12:00:00Z",
  "payload": {
    "translationConfidence": null,
    "riskScore": null,
    "securityCode": null,
    "receiptId": null,
    "providerStatus": null
  }
}
```

Stages: `PENDING`, `VERIFIED`, `GEMINI`, `EXECUTING`, `BANK_PENDING`, `EXECUTED`, `FAILED`, `OUTCOME_UNKNOWN`. Outcomes: `PENDING`, `APPROVED`, `REJECTED`, `ACCEPTED_PENDING`, `COMPLETED`, `FAILED`, `UNKNOWN`. `eventId` is a notification ID, not a durable idempotency guarantee. Confidence (0–1), risk (0–100), and a fixed security reason code can be supplied in `SafePayload`; receipt ID and provider status are read from the transaction row, never accepted as caller overrides. Lore text requires a separately reviewed redaction contract before transport; this milestone does not publish it. The publisher has no fields for tokens, signatures, signing keys, or expected glyphs. Never repurpose a safe field to carry them.

Browser sequence: obtain an Auth0 access token for the existing `https://api.runevault` audience with `read:transactions`; POST `/api/ws-ticket` with `Authorization: Bearer <token>`; connect to `/ws/transfers` with the returned opaque ticket as the single WebSocket subprotocol `runevault.ticket.<ticket>`. The browser sends no access token in the WebSocket URL. The ticket is single-use and expires after at most 30 seconds or at access-token expiry, whichever is earlier. The socket closes at token expiry. On disconnect, obtain a fresh Auth0 token if needed, request a **new** ticket, and reconnect. Angular's `FeedService` performs this when `environment.feedUrl` is configured as `ws://localhost:4200/ws/transfers` for Angular development or `wss://YOUR_DOMAIN/ws/transfers` for HTTPS deployment. Its default empty URL keeps mock mode, and dashboard polling remains unchanged. Do not set a bare backend port in production.

Tickets and sessions are in memory on one backend instance. A restart drops them; there is no replay, durable delivery, ordering guarantee, or multi-instance routing. Bounded ticket/session/queue limits reject excess work. Caddy and Nginx access logs are disabled for this route; never enable request-header or full-URL logging without redacting the subprotocol and sensitive query strings. Client-to-server socket messages are rejected, so this transport cannot approve, mutate, or execute transactions.
