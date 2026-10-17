-- Dashboard fields added after the application schema migrations.
-- IF NOT EXISTS also supports databases previously maintained by Hibernate update.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS gemini_decision_explanation varchar(300);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS new_balance_minor bigint;
