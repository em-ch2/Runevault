-- Flyway baselines pre-existing application tables at V1, then runs this
-- idempotent upgrade. Never reset counters, revocations, claims, or audit rows.
-- Hibernate's old update mode did not widen enum CHECK constraints.
-- Preserve historical SIMULATED rows; Pico approvals become VERIFIED and claims become EXECUTING.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS verified_at timestamp(6) with time zone;
-- Every existing transaction was USD; backfill before claims read currency from storage.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS currency varchar(3);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS device_id varchar(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS expected_glyphs varchar(127);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS expires_at timestamp(6) with time zone;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS requester_subject varchar(255);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS execution_claimed_at timestamp(6) with time zone;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS audit_json text;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS audit_source varchar(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS prompt_version varchar(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS audited_at timestamp(6) with time zone;
ALTER TABLE device_states ADD COLUMN IF NOT EXISTS revoked_at timestamp(6) with time zone;
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS device_id varchar(64);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS transaction_id varchar(36);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS actor_subject varchar(255);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_receipt_id varchar(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_status varchar(64);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_provider varchar(32);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_outcome varchar(32);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_reason varchar(40);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_submitted_at timestamp(6) with time zone;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS bank_outcome_at timestamp(6) with time zone;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS gemini_decision_reason varchar(40);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS gemini_decision_at timestamp(6) with time zone;
UPDATE transactions SET currency = 'USD' WHERE currency IS NULL;
ALTER TABLE transactions
 DROP CONSTRAINT IF EXISTS transactions_status_check,
 ADD CONSTRAINT transactions_status_check CHECK (status IN ('PENDING', 'SIMULATED', 'VERIFIED', 'EXECUTING', 'BANK_PENDING', 'EXECUTED', 'FAILED', 'OUTCOME_UNKNOWN'));
-- Earlier code treated any receipt ID as completion. Reclassify only rows without
-- outcome evidence; keep IDs and receipts, and never infer completion from an ID.
UPDATE transactions SET status = 'BANK_PENDING', bank_outcome = 'ACCEPTED_PENDING',
 bank_provider = 'nessie', bank_reason = 'ACCEPTED_PENDING', bank_outcome_at = updated_at
 WHERE status = 'EXECUTED' AND bank_outcome IS NULL AND lower(bank_status) = 'pending';
UPDATE transactions SET bank_outcome = 'COMPLETED', bank_provider = 'nessie',
 bank_reason = 'CONFIRMED_COMPLETED', bank_outcome_at = updated_at
 WHERE status = 'EXECUTED' AND bank_outcome IS NULL AND lower(bank_status) IN ('completed', 'complete');
UPDATE transactions SET status = 'OUTCOME_UNKNOWN', bank_outcome = 'UNKNOWN',
 bank_provider = 'nessie', bank_reason = 'PROVIDER_STATUS_UNKNOWN', bank_outcome_at = updated_at
 WHERE status = 'EXECUTED' AND bank_outcome IS NULL;
UPDATE transactions SET status = 'OUTCOME_UNKNOWN', bank_outcome = 'UNKNOWN',
 bank_provider = 'nessie', bank_reason = 'SUBMISSION_UNCERTAIN', bank_outcome_at = updated_at
 WHERE status = 'FAILED' AND bank_outcome IS NULL
 AND (bank_status IS NULL OR bank_status NOT IN ('FAILED_UNSUPPORTED_CURRENCY',
  'FAILED_UNKNOWN_DESTINATION', 'FAILED_INSUFFICIENT_FUNDS'));
UPDATE transactions SET bank_outcome = 'FAILED', bank_provider = 'nessie',
 bank_reason = 'PRE_SUBMISSION_FAILURE', bank_outcome_at = updated_at
 WHERE status = 'FAILED' AND bank_outcome IS NULL;

-- Existing databases may have enum CHECK constraints created before policy audits existed.
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_event_type_check,
 ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN ('DEVICE_AUTHORIZATION', 'TRANSACTION_CREATION', 'EXECUTION_CLAIM', 'DEVICE_REVOCATION', 'BANK_OUTCOME', 'GEMINI_DECISION'));
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_outcome_check,
 ADD CONSTRAINT audit_events_outcome_check CHECK (outcome IN ('APPROVED', 'REJECTED', 'PENDING', 'UNKNOWN'));
ALTER TABLE audit_events
 DROP CONSTRAINT IF EXISTS audit_events_reason_check,
 ADD CONSTRAINT audit_events_reason_check CHECK (reason IN (
  'APPROVED', 'UNKNOWN_DEVICE', 'INVALID_SIGNATURE', 'NO_PENDING_DEVICE_TRANSACTION',
  'COUNTER_REPLAY', 'TRANSACTION_EXPIRED', 'TRANSACTION_MISMATCH', 'INCORRECT_GLYPHS',
  'DEVICE_LOCKED', 'AMOUNT_LIMIT_EXCEEDED', 'DESTINATION_NOT_ALLOWED', 'EXECUTION_CLAIMED',
  'DEVICE_REVOKED', 'REVOKED_BY_ADMIN', 'EXECUTION_INELIGIBLE',
  'ACCEPTED_PENDING', 'CONFIRMED_COMPLETED', 'DEFINITE_REJECTION', 'PRE_SUBMISSION_FAILURE',
  'SUBMISSION_UNCERTAIN', 'MALFORMED_RESPONSE', 'PROVIDER_STATUS_UNKNOWN',
  'GEMINI_APPROVED', 'GEMINI_UNAVAILABLE', 'GEMINI_INVALID_RESPONSE', 'GEMINI_LOW_CONFIDENCE', 'GEMINI_HIGH_RISK'));
CREATE INDEX IF NOT EXISTS idx_audit_events_timestamp ON audit_events (timestamp, id);
