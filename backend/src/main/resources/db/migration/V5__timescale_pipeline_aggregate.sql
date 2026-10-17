-- Tiger Cloud should have TimescaleDB installed before this migration. Plain
-- PostgreSQL remains supported with an ordinary pipeline_events table.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
  PERFORM create_hypertable('pipeline_events', 'occurred_at', if_not_exists => TRUE, migrate_data => TRUE);
  EXECUTE 'CREATE MATERIALIZED VIEW IF NOT EXISTS pipeline_events_per_minute '
   || 'WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS '
   || 'SELECT time_bucket(INTERVAL ''1 minute'', occurred_at) AS minute, stage, outcome, '
   || 'count(*) AS events, avg(latency_ms) AS avg_latency_ms FROM pipeline_events '
   || 'GROUP BY minute, stage, outcome WITH NO DATA';
  PERFORM add_continuous_aggregate_policy('pipeline_events_per_minute',
   start_offset => INTERVAL '1 day', end_offset => INTERVAL '1 minute',
   schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);
 END IF;
END $$;
