# Execution handoff and event contracts

## Internal VerifiedTransaction handoff

`DeviceAuthorizationService.authorize` commits physical `VERIFIED`, `verifiedAt`, device-counter consumption, and the approval audit record before `DeviceApi` calls `TransactionProcessor.process(id)`. The processor receives only the transaction ID. It reads the stored glyphs, amount, destination, currency, and device. Gemini runs outside database transactions. Its validated output and fixed decision reason are then persisted with a `GEMINI_DECISION` audit event in a short transaction.

Only a `GEMINI_APPROVED` decision permits `TransactionService.claimPicoAuthorized(id)`. That service locks device then transaction, rechecks revocation, physical evidence, deterministic policy, and the stored Gemini approval, and atomically sets `EXECUTING`/`executionClaimedAt` with an `EXECUTION_CLAIM` audit event. It returns an immutable `VerifiedTransaction` containing the stored ID, device ID, amount in cents, currency, destination, glyphs, status, and timestamps. `ExecutionService` receives this claimed value and calls the bank outside the lock. `BankOutcomeService` saves the provider result and audit event atomically afterward. A claim prevents duplicate local execution attempts; it does not guarantee exactly-once behavior at Nessie. Unknown outcomes must be reviewed before any manual recovery.

## Dashboard and Tiger Data event contract

This internal Java record is **not** Person C's dashboard event contract. The current Angular dashboard reads the authenticated transaction API and polls for status changes. A future Tiger Data event stream needs a separately agreed schema, delivery mechanism, consumer identity, and idempotency key. No Tiger Data publisher or cloud deployment is implemented here. Physical verification, Gemini approval, execution claim, and bank outcome are distinct events and must remain distinguishable in any future stream. Do not publish a `VerifiedTransaction` as if it were a completed transfer.
