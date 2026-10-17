package com.runevault;

import com.runevault.events.PipelineEventWriter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:pipeline-dedupe;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles({"dev", "test"})
class PipelineEventWriterConcurrencyTest {
 @Autowired PipelineEventWriter writer;
 @Autowired JdbcTemplate jdbc;

 @Test void repeatedAndConcurrentPublicationStoresOneEvent() throws Exception {
  UUID id = UUID.randomUUID();
  var row = new PipelineEventWriter.Row(id, Instant.now(), "tx-dedupe", "VERIFIED", "APPROVED",
   "VERIFIED", null, null, null, null);
  int workers = 8;
  var ready = new CountDownLatch(workers);
  var start = new CountDownLatch(1);
  try (var pool = Executors.newFixedThreadPool(workers)) {
   var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
   for (int i = 0; i < workers; i++) tasks.add(pool.submit(() -> {
    ready.countDown(); start.await(); return writer.write(row);
   }));
   assertTrue(ready.await(5, TimeUnit.SECONDS));
   start.countDown();
   int winners = 0;
   for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
   assertEquals(1, winners);
  }
  assertFalse(writer.write(row));
  assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pipeline_events WHERE event_id = ?",
   Integer.class, id));
 }

 @Test void failedEventInsertDoesNotReserveItsId() {
  UUID id = UUID.randomUUID();
  Instant at = Instant.now();
  assertThrows(RuntimeException.class, () -> writer.write(new PipelineEventWriter.Row(
   id, at, null, "VERIFIED", "APPROVED", "VERIFIED", null, null, null, null)));
  assertTrue(writer.write(new PipelineEventWriter.Row(
   id, at, "tx-retry", "VERIFIED", "APPROVED", "VERIFIED", null, null, null, null)));
 }
}
