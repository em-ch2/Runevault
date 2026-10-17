package com.runevault.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only pipeline event writer. Production schema belongs to Flyway. */
@Component
public class PipelineEventWriter {
 public record Row(UUID eventId, Instant occurredAt, String transactionId, String stage, String outcome,
   String status, String reasonCode, Double riskScore, Double translationConfidence, Long latencyMs) {}

 private final JdbcTemplate jdbc;
 private final TransactionTemplate transaction;

 public PipelineEventWriter(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment environment) {
  this.jdbc = jdbc;
  this.transaction = new TransactionTemplate(manager);
  // H2 tests do not run PostgreSQL Flyway migrations; create only their test tables here.
  String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
  if (environment.acceptsProfiles(Profiles.of("test")) && "H2".equals(product)) {
   jdbc.execute("CREATE TABLE IF NOT EXISTS pipeline_events (event_id uuid NOT NULL, "
    + "occurred_at timestamp with time zone NOT NULL, transaction_id varchar(36) NOT NULL, "
    + "stage varchar(32) NOT NULL, outcome varchar(32) NOT NULL, status varchar(32), "
    + "reason_code varchar(64), risk_score double precision, translation_confidence double precision, "
    + "latency_ms bigint, PRIMARY KEY (occurred_at, event_id))");
   jdbc.execute("CREATE TABLE IF NOT EXISTS pipeline_event_keys (event_id uuid PRIMARY KEY)");
  }
 }

 /** Atomically reserve the global ID and append the event after its stage commits. */
 public boolean write(Row row) {
  if (TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Record pipeline events only after the transaction commits");
  return Boolean.TRUE.equals(transaction.execute(status -> {
   int inserted = jdbc.update("INSERT INTO pipeline_event_keys (event_id) VALUES (?) ON CONFLICT DO NOTHING",
    row.eventId());
   if (inserted == 0) return false;
   jdbc.update("""
     INSERT INTO pipeline_events (event_id, occurred_at, transaction_id, stage, outcome, status,
       reason_code, risk_score, translation_confidence, latency_ms)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     """, row.eventId(), Timestamp.from(row.occurredAt()), row.transactionId(), row.stage(),
    row.outcome(), row.status(), row.reasonCode(), row.riskScore(), row.translationConfidence(), row.latencyMs());
   return true;
  }));
 }
}
