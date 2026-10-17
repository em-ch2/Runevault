-- Expand the existing status constraint without rewriting or deleting prior receipts.
ALTER TABLE transactions
 DROP CONSTRAINT IF EXISTS transactions_status_check,
 ADD CONSTRAINT transactions_status_check CHECK (status IN
 ('PENDING','VERIFIED','AUDITED','APPROVAL_PENDING','APPROVED','EXECUTING',
  'SIMULATED','EXECUTED','FAILED','EXPIRED','EXECUTION_UNKNOWN','REJECTED'));
