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

 private final JdbcTemplate jdbc;
 private final PipelineEventWriter writer;
 private final GeminiPolicy geminiPolicy;

 public PipelineMetricsApi(JdbcTemplate jdbc, PipelineEventWriter writer, GeminiPolicy geminiPolicy) {
  this.jdbc = jdbc;
  this.writer = writer;
  this.geminiPolicy = geminiPolicy;
 }

 @GetMapping("/pipeline")
 @PreAuthorize("hasAuthority('PERM_read:transactions')")
 public PipelineMetrics pipeline(@RequestParam(defaultValue = "60") int minutes) {
  if (minutes < 1 || minutes > 1440)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be 1-1440");
  Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
  Instant from = to.minus(minutes - 1L, ChronoUnit.MINUTES);
  Map<Instant, long[]> buckets = new LinkedHashMap<>();
  for (Instant m = from; !m.isAfter(to); m = m.plus(1, ChronoUnit.MINUTES)) buckets.put(m, new long[2]);

  String source;
  if (writer.timescaleEnabled()) {
   source = "timescale-continuous-aggregate";
   jdbc.query("SELECT minute, stage, outcome, events FROM pipeline_events_per_minute WHERE minute >= ?",
    rs -> { add(buckets, rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getLong(4)); },
    Timestamp.from(from));
  } else {
   source = "pipeline-events";
   jdbc.query("SELECT occurred_at, stage, outcome FROM pipeline_events WHERE occurred_at >= ?",
    rs -> { add(buckets, rs.getTimestamp(1), rs.getString(2), rs.getString(3), 1); },
    Timestamp.from(from));
  }
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

 private static void add(Map<Instant, long[]> buckets, Timestamp at, String stage, String outcome, long count) {
  long[] bucket = buckets.get(at.toInstant().truncatedTo(ChronoUnit.MINUTES));
  if (bucket == null) return;
  if ("GEMINI".equals(stage) && "APPROVED".equals(outcome)) bucket[0] += count;
  else if (("GEMINI".equals(stage) || "VERIFIED".equals(stage)) && "REJECTED".equals(outcome)) bucket[1] += count;
 }
}
