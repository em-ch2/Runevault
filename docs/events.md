# Execution handoff and event contracts

Two contracts live here:

1. **Internal `VerifiedTransaction` hand-off**: how a physically verified transaction reaches the bank.
2. **Dashboard events**: what the backend pushes to the Angular dashboard over `/ws/transfers` (owner: C).

Change a contract only in a pull request that updates the backend, the frontend parser (`frontend/src/app/feed/feed-event.ts`) and this file together.

## 1. Internal VerifiedTransaction handoff

`DeviceAuthorizationService.authorize` commits physical `VERIFIED`, `verifiedAt`, device-counter consumption, and the approval audit record before `DeviceApi` calls `TransactionProcessor.process(id)`. The processor receives only the transaction ID. It reads the stored glyphs, amount, destination, currency, and device. Gemini runs outside database transactions. Its validated output and fixed decision reason are then persisted with a `GEMINI_DECISION` audit event in a short transaction.

Only a `GEMINI_APPROVED` decision permits `TransactionService.claimPicoAuthorized(id)`. That service locks device then transaction, rechecks revocation, physical evidence, deterministic policy, and the stored Gemini approval, and atomically sets `EXECUTING`/`executionClaimedAt` with an `EXECUTION_CLAIM` audit event. It returns an immutable `VerifiedTransaction` containing the stored ID, device ID, amount in cents, currency, destination, glyphs, status, and timestamps. `ExecutionService` receives this claimed value and calls the bank outside the lock. `BankOutcomeService` saves the provider result and audit event atomically afterward. A claim prevents duplicate local execution attempts; it does not guarantee exactly-once behavior at Nessie. Unknown outcomes must be reviewed before any manual recovery.

## 2. Dashboard events

**Status:** agreed format, not yet delivered. Today the dashboard reads the authenticated transaction API and polls for status changes. The `/ws/transfers` WebSocket (B) will send these events; the same events are written to the Tiger Data `pipeline_events` table. Physical verification, Gemini decision, execution claim, and bank outcome are separate events and must stay distinguishable. Never publish a `VerifiedTransaction` as if it were a completed transfer.

The backend sends one JSON object per WebSocket message. Each pipeline stage sends one event, in this order:

```
transfer_pending → transfer_sealed → transfer_verified → transfer_audited → transfer_executed
                                   ↘ transfer_rejected   ↘ transfer_blocked   ↘ transfer_failed
                                                                              ↘ transfer_unknown
```

A transfer ends with exactly one of `transfer_executed`, `transfer_rejected`, `transfer_blocked`, `transfer_failed` or `transfer_unknown`. `tablet_status` events are independent of any transfer.

### Rules for every event

| Field | Type | Rule |
| --- | --- | --- |
| `type` | string | One of the event types below. The dashboard ignores unknown types. |
| `tx_id` | string | Transaction ID, 1–36 characters `[A-Za-z0-9_-]`. Absent only on `tablet_status`. |
| `at` | string | Time the stage finished, ISO-8601 UTC, e.g. `2026-10-17T15:22:53.965Z`. |

- Money is always an **integer number of cents** (`amount`, `new_balance`).
- Events never contain the bridge token, device key, Pico key, Auth0 tokens or Nessie/Gemini API keys.
- Glyphs are the approval secret, so they appear **only** in `transfer_executed`.
- Text from Gemini or the bank is displayed as plain text, never as HTML.
- The dashboard re-validates every event (`parseFeedEvent`) and drops anything that doesn't match.

### `tablet_status`

| Field | Type | Values |
| --- | --- | --- |
| `device` | string | e.g. `slab-01` |
| `state` | string | `connected`, `locked`, `disconnected` |

### `transfer_pending`

An employee created a transfer. Status `PENDING`.

| Field | Type | Notes |
| --- | --- | --- |
| `amount` | integer | Cents |
| `dest` | string | Destination account ID (Nessie account UUID) |
| `recipient` | string | Display name, up to 120 characters |
| `requester` | string | Auth0 name or email, up to 120 characters |
| `expires_at` | string | ISO-8601 UTC; must be signed before this |

### `transfer_sealed`

The bridge delivered a signed authorization. Not yet checked.

| Field | Type | Notes |
| --- | --- | --- |
| `device` | string | Signing device |
| `counter` | integer | Device counter used |
| `sig` | string | 64 hex characters (not a secret) |

### `transfer_verified`

Signature, transaction match, counter, glyphs and limits passed. Status `VERIFIED`. No money has moved.

### `transfer_rejected`

Verification failed. Nothing moved; Gemini was not called. Terminal.

| Field | Type | Notes |
| --- | --- | --- |
| `reason_code` | string | A verification reason from the table below |
| `reason` | string | Plain-English explanation, up to 300 characters |

### `transfer_audited`

Gemini returned a valid assessment and a decision was recorded (`GEMINI_DECISION` audit event).

| Field | Type | Notes |
| --- | --- | --- |
| `lore_translation` | string | Fictional Poneglyph lore, up to 2000 characters; never reveals the glyphs |
| `translation_confidence` | number | 0.0–1.0 |
| `risk_score` | integer | 0–100 |
| `risk_flags` | string[] | Up to 5 flags, each up to 80 characters |
| `risk_explanation` | string | Up to 500 characters |
| `security_message` | string | Plain-English status, up to 300 characters |
| `decision` | string | `GEMINI_APPROVED`, `GEMINI_LOW_CONFIDENCE` or `GEMINI_HIGH_RISK` |

> Gemini currently returns the `lore-risk-v1` shape (`lore.title`, `lore.text`, `risk.level`, `risk.reasons`, `translation_confidence`, `risk_score`). Moving the prompt and `LoreAuditService.validate` to the six fields above, enforced with a Gemini `responseSchema`, is pending (C).

### `transfer_blocked`

The backend refused the transfer after Gemini. Nothing moved. Status stays `VERIFIED` with a stored `geminiDecisionReason`. Terminal.

| Field | Type | Notes |
| --- | --- | --- |
| `reason_code` | string | A Gemini decision reason from the table below |
| `policy_reason` | string | Deterministic reason, e.g. `Risk score 72 is above the limit of 30.` |
| `security_message` | string or null | Gemini's explanation; `null` when Gemini failed |
| `risk_score` | integer or null | `null` when Gemini failed |
| `translation_confidence` | number or null | `null` when Gemini failed |

### `transfer_executed`

The bank accepted the transfer. Terminal.

| Field | Type | Notes |
| --- | --- | --- |
| `glyphs` | string | The signed glyph sequence, e.g. `0-1-2` |
| `receipt` | object | See below |

`receipt`:

| Field | Type | Notes |
| --- | --- | --- |
| `receipt_id` | string | Nessie transfer ID, or `fake-<tx_id>` offline |
| `provider` | string | `nessie` or `fake` |
| `outcome` | string | `ACCEPTED_PENDING` (status `BANK_PENDING`) or `COMPLETED` (status `EXECUTED`) |
| `bank_status` | string | Status text reported by the bank |
| `amount` | integer | Cents |
| `currency` | string | `USD` |
| `dest` | string | Destination account |
| `new_balance` | integer or null | Source balance after the transfer, in cents. **Pending:** not yet captured by `NessieBankGateway`; `null` until then. |
| `submitted_at` | string | ISO-8601 UTC |

### `transfer_failed`

The bank definitely did not move the money. Status `FAILED`. Terminal.

| Field | Type | Notes |
| --- | --- | --- |
| `reason_code` | string | `DEFINITE_REJECTION` or `PRE_SUBMISSION_FAILURE` |
| `reason` | string | Plain-English explanation |

### `transfer_unknown`

The backend can't tell whether money moved. Status `OUTCOME_UNKNOWN`. Terminal until someone checks Nessie. **Never retried automatically.**

| Field | Type | Notes |
| --- | --- | --- |
| `reason_code` | string | `SUBMISSION_UNCERTAIN`, `MALFORMED_RESPONSE` or `PROVIDER_STATUS_UNKNOWN` |
| `reason` | string | e.g. `The bank did not answer. Check the Nessie account before trying again.` |

### Reason codes

#### Verification reasons (`transfer_rejected`)

These match `AuditEvent.Reason`.

| Code | Meaning |
| --- | --- |
| `UNKNOWN_DEVICE` | The signing device isn't registered |
| `INVALID_SIGNATURE` | The signature doesn't match (tampered packet) |
| `COUNTER_REPLAY` | The counter was already used (replayed packet) |
| `TRANSACTION_MISMATCH` | Amount, destination or device differ from the pending transfer |
| `TRANSACTION_EXPIRED` | Not signed in time |
| `INCORRECT_GLYPHS` | Wrong glyph sequence |
| `DEVICE_LOCKED` | Too many wrong glyph attempts |
| `DEVICE_REVOKED` | Revoked by an admin |
| `AMOUNT_LIMIT_EXCEEDED` | Above the configured maximum |
| `DESTINATION_NOT_ALLOWED` | Not on the allowed list |

#### Gemini decision reasons (`transfer_audited`, `transfer_blocked`)

These match `AuditEvent.Reason` and `GeminiPolicy`.

| Code | Meaning |
| --- | --- |
| `GEMINI_APPROVED` | Passed both thresholds; the transfer may be claimed |
| `GEMINI_UNAVAILABLE` | Gemini timed out, errored or isn't configured |
| `GEMINI_INVALID_RESPONSE` | Gemini's answer was missing fields or broke the schema |
| `GEMINI_LOW_CONFIDENCE` | `translation_confidence` below `RUNEVAULT_MIN_TRANSLATION_CONFIDENCE` (default 0.8) |
| `GEMINI_HIGH_RISK` | `risk_score` above `RUNEVAULT_MAX_RISK_SCORE` (default 30) |

### Changes the frontend parser needs

`feed-event.ts` must be updated to match:

- `transfer_pending` gains `expires_at`.
- `transfer_rejected` gains `reason_code`; `reason` grows to 300 characters.
- `transfer_executed` drops `lore`, `risk_level` and `bank_ref`. Lore and scores arrive in `transfer_audited`; the bank reference is `receipt.receipt_id`.
- New types: `transfer_audited`, `transfer_blocked`, `transfer_failed`, `transfer_unknown`.
- `mock-feed.ts` scripts must be updated so demo mode matches.
