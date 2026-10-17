-- Hibernate's update mode does not widen an existing enum CHECK constraint.
-- Preserve historical SIMULATED rows; Pico approvals become VERIFIED and claims become EXECUTING.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS verified_at timestamp(6) with time zone;
-- Every existing transaction was USD; backfill before claims read currency from storage.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS currency varchar(3);
ALTER TABLE device_states ADD COLUMN IF NOT EXISTS revoked_at timestamp(6) with time zone;
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS actor_subject varchar(255);
UPDATE transactions SET currency = 'USD' WHERE currency IS NULL;
ALTER TABLE transactions
 DROP CONSTRAINT IF EXISTS transactions_status_check,
 ADD CONSTRAINT transactions_status_check CHECK (status IN ('PENDING', 'SIMULATED', 'VERIFIED', 'EXECUTING', 'EXECUTED', 'FAILED'));

-- Existing databases may have enum CHECK constraints created before policy audits existed.
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_event_type_check,
 ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN ('DEVICE_AUTHORIZATION', 'TRANSACTION_CREATION', 'EXECUTION_CLAIM', 'DEVICE_REVOCATION'));
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_reason_check,
 ADD CONSTRAINT audit_events_reason_check CHECK (reason IN (
  'APPROVED', 'UNKNOWN_DEVICE', 'INVALID_SIGNATURE', 'NO_PENDING_DEVICE_TRANSACTION',
  'COUNTER_REPLAY', 'TRANSACTION_EXPIRED', 'TRANSACTION_MISMATCH', 'INCORRECT_GLYPHS',
  'DEVICE_LOCKED', 'AMOUNT_LIMIT_EXCEEDED', 'DESTINATION_NOT_ALLOWED', 'EXECUTION_CLAIMED',
  'DEVICE_REVOKED', 'REVOKED_BY_ADMIN', 'EXECUTION_INELIGIBLE'));
