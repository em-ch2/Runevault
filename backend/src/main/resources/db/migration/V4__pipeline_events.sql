-- Preserve existing pipeline events; a separate key table enforces global event IDs
-- because Timescale hypertable uniqueness must include the time partition column.
CREATE TABLE IF NOT EXISTS pipeline_events (
 event_id uuid NOT NULL,
 occurred_at timestamp with time zone NOT NULL,
 transaction_id varchar(36) NOT NULL,
 stage varchar(32) NOT NULL,
 outcome varchar(32) NOT NULL,
 status varchar(32),
 reason_code varchar(64),
 risk_score double precision,
 translation_confidence double precision,
 latency_ms bigint,
 PRIMARY KEY (occurred_at, event_id)
);
CREATE INDEX IF NOT EXISTS idx_pipeline_events_tx ON pipeline_events (transaction_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_pipeline_events_event ON pipeline_events (event_id);
CREATE TABLE IF NOT EXISTS pipeline_event_keys (event_id uuid PRIMARY KEY);
INSERT INTO pipeline_event_keys (event_id)
 SELECT DISTINCT event_id FROM pipeline_events WHERE true ON CONFLICT DO NOTHING;
