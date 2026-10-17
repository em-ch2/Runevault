-- Hibernate's update mode does not widen an existing enum CHECK constraint.
-- Preserve historical SIMULATED rows; only new Pico approvals become VERIFIED.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS verified_at timestamp(6) with time zone;
ALTER TABLE transactions
 DROP CONSTRAINT IF EXISTS transactions_status_check,
 ADD CONSTRAINT transactions_status_check CHECK (status IN ('PENDING', 'SIMULATED', 'VERIFIED'));

-- Existing databases may have enum CHECK constraints created before policy audits existed.
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_event_type_check,
 ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN ('DEVICE_AUTHORIZATION', 'TRANSACTION_CREATION'));
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_reason_check,
 ADD CONSTRAINT audit_events_reason_check CHECK (reason IN (
  'APPROVED', 'UNKNOWN_DEVICE', 'INVALID_SIGNATURE', 'NO_PENDING_DEVICE_TRANSACTION',
  'COUNTER_REPLAY', 'TRANSACTION_EXPIRED', 'TRANSACTION_MISMATCH', 'INCORRECT_GLYPHS',
  'DEVICE_LOCKED', 'AMOUNT_LIMIT_EXCEEDED', 'DESTINATION_NOT_ALLOWED'));
