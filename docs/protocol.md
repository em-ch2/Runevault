# Signing contract v1 (A + B)

## Pico 2 authorization

Bridge URL on the backend machine: `http://localhost:8080/api/device/authorize`.
Send `Content-Type: application/json` and `X-Bridge-Token: <RUNEVAULT_BRIDGE_TOKEN>`.
Compose binds the backend to localhost; a remote bridge needs a separately configured reachable backend URL.

Configure `RUNEVAULT_PICO_DEVICE_ID=slab-01` and `RUNEVAULT_PICO_KEY` with the UTF-8 key provisioned on the device. There is no default key; Pico endpoints return 503 when it is absent. The existing demo signing key is separate and is not used for Pico authorization. This minimal implementation supports one configured device/key, with durable device state in PostgreSQL.

On startup, Hibernate adds the new nullable transaction fields and device table. The PostgreSQL initialization script widens the existing status CHECK constraint to allow `PENDING` without deleting receipts. This retains the demo schema-update approach; production migrations remain outside this hackathon change.

Before authorization, the trusted backend client creates a pending transaction using `POST /api/device/transactions`, with the same bridge header:

```json
{"device":"slab-01","amount":50000,"dest":"acct-999","glyphs":"0-2-1-2-1"}
```

The server returns HTTP 201 with `tx_id`, `status: PENDING`, and `expires_at` (Unix seconds). Pass that generated transaction ID to the device. Pending transactions expire after 120 seconds. The glyph sequence on file comes from this trusted setup request, never from the authorization request. Keep the bridge credential with trusted software; it grants pending-transaction creation as well as access to the existing challenge flow.

For a dashboard-created pending transaction, the bridge may read `GET /api/device/transactions/<tx_id>?device=<device>` with the same `X-Bridge-Token`. A live assignment returns only `tx_id`, `amount`, `dest`, `device`, `status`, and `expires_at`. Missing, expired, consumed, or wrongly assigned transactions are rejected. The bridge sends the returned amount and destination to the Pico; no caller override is accepted in existing-transaction mode.

The device signs the following UTF-8 string with HMAC-SHA256, with literal pipe separators and no trailing newline:

```text
v1|<device>|<counter>|<tx_id>|<amount>|<dest>|<glyphs>
```

`v` must be 1. `amount` is an integer in cents (1–100000000). `counter` is a nonnegative signed 64-bit integer and must increase for each attempt. Glyphs contain 1–64 values from 0–2 separated by dashes. Device and destination use 1–64 ASCII letters, digits, underscores or dashes; transaction IDs use the same characters with a maximum length of 36. `sig` is 64 hexadecimal characters.

```json
{"v":1,"device":"slab-01","counter":2,"tx_id":"abc123","amount":50000,"dest":"acct-999","glyphs":"0-2-1-2-1","sig":"16e6adc714f04bf58c98d0ffc343b31ecd560ed418ad9d1158d6321c2021563b"}
```

That sample is a signing test vector; `abc123` must actually exist as a matching pending transaction to authorize it. The supplied firmware development key was verified against this vector. To run the optional golden-vector JUnit test, supply that key through `PICO_TEST_VECTOR_KEY`; normal tests generate an ephemeral key and need no secrets.

Successful authorization returns HTTP 200 with `transactionId`, `status: SIMULATED`, and `signatureVerified: true`. It updates the existing transaction; no money moves. A database transaction and row locks serialize device counters and transaction consumption.

Person C's execution pipeline can call `TransactionService.claimPicoAuthorized(id)` only after the authorization request commits. This method locks the persisted row, requires a Pico-authorized `SIMULATED` transaction, writes a durable one-time execution claim, and returns a `VerifiedTransaction`. A second claim, including after restart, fails. The claim is at-most-once: if execution fails after claiming, investigate rather than automatically retry. This handoff does not itself invoke the bank gateway.

- 400: malformed/unsupported fields.
- 401: missing/wrong bridge token, unknown configured device, or invalid signature.
- 403: wrong glyph sequence (attempts 1–4).
- 409: replayed counter, missing/expired/consumed transaction, or device/amount/destination mismatch.
- 423: fifth wrong glyph sequence, or a previously locked device.
- 503: missing Pico key configuration.

Every fresh, validly signed attempt consumes its counter, including transaction mismatches and wrong glyphs. Invalid signatures do not mutate device state. Only wrong glyphs for a matching pending transaction count toward the five-attempt lockout. A successful authorization resets consecutive wrong attempts. Lockout and counters survive application restarts and apply across transactions. Lockout has no automatic expiry; a trusted operator must reset `device_states.failed_attempts` while retaining `last_counter`. No public unlock endpoint is exposed.

## Existing challenge/approval flow

POST /api/challenges: {"deviceId":"pico-demo-1","amountMinor":2500,"destination":"demo-harbor","glyphs":"ABCD"}
Returns challengeId, expiresAt (Unix seconds), intent and signingText. Challenge expires in 120 seconds.

HMAC-SHA256 over UTF-8 text with exactly LF separators, no trailing newline:

```
runevault-v1
<challengeId>
<deviceId>
<amountMinor>
USD
<destination>
<glyphs>
<expiresAt>
```

POST /api/approvals: {"challengeId":"...","signature":"lowercase 64-character hex"}

Backend uses the stored intent, constant-time signature comparison and atomic challenge consumption. In-memory state is local only; persistence is required before multiple replicas. Invalid signatures do not consume challenges.

USB proposal: one JSON object per LF-terminated line, maximum 4096 bytes. Host sends type=challenge plus challenge fields; device returns type=approval plus challengeId and signature. Device reconstructs canonical text from validated fields and requires the physical glyph sequence. It must not sign arbitrary host-provided bytes.

No trusted device display means the user cannot independently verify amount/destination. USB-connected means offline signing token, not fully air-gapped. Real bridge holds no signing key. simulator.mjs is test-only and deliberately uses a public demo key.

Proposed Gemini schema: {"lore":"string","riskLevel":"LOW|MEDIUM|HIGH","reasons":["string"]}. Validate output; backend rules control authorization. The unrelated empathy schema from the original brief is not applicable.

Bridge requests now require X-Bridge-Token. Local default: local-bridge-only-change-before-deploy. Override via RUNEVAULT_BRIDGE_TOKEN; never share the device HMAC key with the real bridge.
