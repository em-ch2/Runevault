-- Hibernate's update mode does not widen an existing enum CHECK constraint.
-- Keep existing simulation receipts while allowing the new pending state.
ALTER TABLE transactions
 DROP CONSTRAINT IF EXISTS transactions_status_check,
 ADD CONSTRAINT transactions_status_check CHECK (status IN ('PENDING', 'SIMULATED'));
