package com.runevault.events;

import com.runevault.transactions.GeminiPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Approvals versus blocks per minute from pipeline_events. Counts only; no transaction details.
 * Approved = Gemini policy approvals. Blocked = Gemini policy blocks plus rejected signed approvals.
 */
@RestController
@RequestMapping("/api/metrics")
public class PipelineMetricsApi {
 public record MinutePoint(Instant minute, long approved, long blocked) {}
 public record Policy(BigDecimal minTranslationConfidence, BigDecimal maxRiskScore) {}
 public record PipelineMetrics(Instant from, Instant to, String source, long approved, long blocked,
   List<MinutePoint> minutes, Policy policy) {}
 public record PolicyMetrics(Instant from, Instant to, String source, String measure,
   long approved, long blocked, List<MinutePoint> minutes) {}

 private final JdbcTemplate jdbc;
 private final GeminiPolicy geminiPolicy;

 public PipelineMetricsApi(JdbcTemplate jdbc, GeminiPolicy geminiPolicy) {
  this.jdbc = jdbc;
  this.geminiPolicy = geminiPolicy;
 }

 @GetMapping("/pipeline")
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public PipelineMetrics pipeline(@RequestParam(defaultValue = "60") int minutes, @AuthenticationPrincipal Jwt jwt) {
  if (minutes < 1 || minutes > 1440)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be 1-1440");
  Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
  Instant from = to.minus(minutes - 1L, ChronoUnit.MINUTES);
  Map<Instant, long[]> buckets = new LinkedHashMap<>();
  for (Instant m = from; !m.isAfter(to); m = m.plus(1, ChronoUnit.MINUTES)) buckets.put(m, new long[2]);

  // The aggregate has no owner dimension; join raw events to transactions before grouping.
  String source = "pipeline-events-user";
  jdbc.query("SELECT e.occurred_at, e.stage, e.outcome FROM pipeline_events e "
    + "JOIN transactions t ON t.transaction_id = e.transaction_id "
    + "WHERE e.occurred_at >= ? AND t.requester_subject = ?",
   rs -> { add(buckets, rs.getTimestamp(1), rs.getString(2), rs.getString(3), 1); },
   Timestamp.from(from), jwt.getSubject());
  List<MinutePoint> points = new ArrayList<>();
  long approved = 0, blocked = 0;
  for (var entry : buckets.entrySet()) {
   points.add(new MinutePoint(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
   approved += entry.getValue()[0];
   blocked += entry.getValue()[1];
  }
  // The configured thresholds, so dashboard gauges mark the limits the backend actually enforces.
  return new PipelineMetrics(from, to, source, approved, blocked, points,
   new Policy(geminiPolicy.minimumConfidence(), geminiPolicy.maximumRisk()));
 }

 /** Company-wide Gemini policy decisions; stage transitions and physical attempts are excluded. */
 @GetMapping("/policy-decisions")
 @PreAuthorize("hasAnyAuthority('PERM_read:audit', 'PERM_admin:platform')")
 public PolicyMetrics policyDecisions(@RequestParam(defaultValue = "30") int minutes) {
  if (minutes < 1 || minutes > 1440)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be 1-1440");
  Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
  Instant from = to.minus(minutes - 1L, ChronoUnit.MINUTES);
  Map<Instant, long[]> buckets = new LinkedHashMap<>();
  for (Instant m = from; !m.isAfter(to); m = m.plus(1, ChronoUnit.MINUTES)) buckets.put(m, new long[2]);

  String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
  boolean timescale = "PostgreSQL".equals(product) && Boolean.TRUE.equals(jdbc.queryForObject(
   "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb')", Boolean.class));
  String source;
  if (timescale) {
   // The real-time continuous aggregate includes the current, not-yet-materialized minute.
   jdbc.query("""
     SELECT minute,
       SUM(CASE WHEN stage = 'GEMINI' AND outcome = 'APPROVED' THEN events ELSE 0 END),
       SUM(CASE WHEN stage = 'GEMINI' AND outcome = 'REJECTED' THEN events ELSE 0 END)
     FROM pipeline_events_per_minute WHERE minute >= ? AND minute < ?
     GROUP BY minute ORDER BY minute
     """, rs -> {
      long[] bucket = buckets.get(rs.getTimestamp(1).toInstant());
      if (bucket != null) { bucket[0] = rs.getLong(2); bucket[1] = rs.getLong(3); }
     }, Timestamp.from(from), Timestamp.from(to.plus(1, ChronoUnit.MINUTES)));
   source = "timescale-continuous-aggregate";
  } else {
   jdbc.query("""
     SELECT occurred_at, outcome FROM pipeline_events
     WHERE occurred_at >= ? AND occurred_at < ? AND stage = 'GEMINI'
       AND outcome IN ('APPROVED', 'REJECTED')
     """, rs -> {
      long[] bucket = buckets.get(rs.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.MINUTES));
      if (bucket != null) bucket["APPROVED".equals(rs.getString(2)) ? 0 : 1]++;
     }, Timestamp.from(from), Timestamp.from(to.plus(1, ChronoUnit.MINUTES)));
   source = "PostgreSQL".equals(product) ? "raw-postgresql-events" : "raw-test-events";
  }
  List<MinutePoint> points = new ArrayList<>();
  long approved = 0, blocked = 0;
  for (var entry : buckets.entrySet()) {
   points.add(new MinutePoint(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
   approved += entry.getValue()[0]; blocked += entry.getValue()[1];
  }
  return new PolicyMetrics(from, to, source, "gemini-policy-decisions", approved, blocked, points);
 }

 private static void add(Map<Instant, long[]> buckets, Timestamp at, String stage, String outcome, long count) {
  long[] bucket = buckets.get(at.toInstant().truncatedTo(ChronoUnit.MINUTES));
  if (bucket == null) return;
  if ("GEMINI".equals(stage) && "APPROVED".equals(outcome)) bucket[0] += count;
  else if (("GEMINI".equals(stage) || "VERIFIED".equals(stage)) && "REJECTED".equals(outcome)) bucket[1] += count;
 }
}
