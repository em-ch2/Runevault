package com.runevault.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable, append-only Tiger Data record of every committed pipeline stage. Separate from the
 * best-effort WebSocket notification: this table is for audit trails and metrics.
 * Holds only fixed codes and validated numbers: no lore, keys, signatures, counters or glyphs.
 *
 * Schema ownership: this class creates pipeline_events (and, on TimescaleDB, its hypertable and
 * pipeline_events_per_minute continuous aggregate) idempotently at startup. If database bootstrap
 * moves to migration files, move these statements there and delete afterPropertiesSet.
 */
@Component
public class PipelineEventWriter implements InitializingBean {
 private static final Logger log = LoggerFactory.getLogger(PipelineEventWriter.class);

 public record Row(UUID eventId, Instant occurredAt, String transactionId, String stage, String outcome,
   String status, String reasonCode, Double riskScore, Double translationConfidence, Long latencyMs) {}

 private final JdbcTemplate jdbc;
 private volatile boolean timescale;

 public PipelineEventWriter(JdbcTemplate jdbc) {
  this.jdbc = jdbc;
 }

 public boolean timescaleEnabled() { return timescale; }

 @Override public void afterPropertiesSet() {
  jdbc.execute("""
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
      PRIMARY KEY (occurred_at, event_id))
    """);
  jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pipeline_events_tx ON pipeline_events (transaction_id, occurred_at)");
  jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pipeline_events_event ON pipeline_events (event_id)");
  timescale = enableTimescale();
 }

 // Hypertable plus a one-minute continuous aggregate when the TimescaleDB extension exists.
 // Without it (plain PostgreSQL, H2 in tests) pipeline_events stays an ordinary table.
 private boolean enableTimescale() {
  try {
   String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
   if (!"PostgreSQL".equals(product)) return false;
   Boolean available = jdbc.queryForObject(
    "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb')", Boolean.class);
   if (!Boolean.TRUE.equals(available)) {
    log.info("TimescaleDB is not available; pipeline_events is a plain PostgreSQL table");
    return false;
   }
   jdbc.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
   jdbc.execute("SELECT create_hypertable('pipeline_events', 'occurred_at', if_not_exists => TRUE, migrate_data => TRUE)");
   jdbc.execute("""
     CREATE MATERIALIZED VIEW IF NOT EXISTS pipeline_events_per_minute
     WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
     SELECT time_bucket(INTERVAL '1 minute', occurred_at) AS minute, stage, outcome,
       count(*) AS events, avg(latency_ms) AS avg_latency_ms
     FROM pipeline_events
     GROUP BY minute, stage, outcome
     WITH NO DATA
     """);
   jdbc.execute("""
     SELECT add_continuous_aggregate_policy('pipeline_events_per_minute',
       start_offset => INTERVAL '1 day', end_offset => INTERVAL '1 minute',
       schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE)
     """);
   log.info("pipeline_events is a TimescaleDB hypertable with a one-minute continuous aggregate");
   return true;
  } catch (RuntimeException e) {
   log.warn("TimescaleDB setup for pipeline_events failed; continuing with a plain table", e);
   return false;
  }
 }

 /**
  * Appends one stage unless a row with the same event ID already exists. Call only after the
  * stage's own database transaction has committed. Returns false for a duplicate.
  */
 public boolean write(Row row) {
  if (TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Record pipeline events only after the transaction commits");
  Integer existing = jdbc.queryForObject("SELECT count(*) FROM pipeline_events WHERE event_id = ?",
   Integer.class, row.eventId());
  if (existing != null && existing > 0) return false;
  jdbc.update("""
    INSERT INTO pipeline_events (event_id, occurred_at, transaction_id, stage, outcome, status,
      reason_code, risk_score, translation_confidence, latency_ms)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """,
   row.eventId(), Timestamp.from(row.occurredAt()), row.transactionId(), row.stage(), row.outcome(),
   row.status(), row.reasonCode(), row.riskScore(), row.translationConfidence(), row.latencyMs());
  return true;
 }
}
