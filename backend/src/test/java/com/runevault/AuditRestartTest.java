package com.runevault;

import java.nio.file.Path;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.*;

class AuditRestartTest {
 @TempDir Path directory;

 @Test void rejectedAuditEventSurvivesApplicationRestart() {
  String databaseUrl = "jdbc:h2:file:" + directory.resolve("audit-db").toAbsolutePath().toString().replace('\\', '/');
  String key = UUID.randomUUID().toString();
  String[] arguments = {
   "--server.port=0",
   "--spring.datasource.url=" + databaseUrl,
   "--spring.jpa.hibernate.ddl-auto=update",
   "--spring.sql.init.mode=never",
   "--runevault.bridge-token=test-bridge-token-with-enough-characters",
   "--runevault.pico-key=" + key};
  try (var first = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test")
   .run(arguments)) {
   var service = first.getBean(DeviceAuthorizationService.class);
   var pending = service.createPending(new DeviceApi.Pending("slab-01", 50000L, "acct-999", "0-2-1-2-1"));
   String id = (String) pending.getBody().get("tx_id");
   var response = service.authorize(new DeviceApi.Authorization(1, "slab-01", 1L, id,
    50000L, "acct-999", "0-2-1-2-1", "0".repeat(64)));
   assertEquals(401, response.getStatusCode().value());
   assertEquals(1, first.getBean(AuditEventRepository.class).count());
  }
  try (var second = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test")
   .run(arguments)) {
   var event = second.getBean(AuditEventRepository.class).findAll().get(0);
   assertEquals(AuditEvent.Outcome.REJECTED, event.getOutcome());
   assertEquals(AuditEvent.Reason.INVALID_SIGNATURE, event.getReason());
   assertEquals("slab-01", event.getDeviceId());
  }
 }

 @Test void consumedCounterAndApprovalSurviveRestart() throws Exception {
  String databaseUrl = "jdbc:h2:file:" + directory.resolve("replay-db").toAbsolutePath().toString().replace('\\', '/');
  String key = UUID.randomUUID().toString();
  String[] arguments = {"--server.port=0", "--spring.datasource.url=" + databaseUrl,
   "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=never",
   "--runevault.bridge-token=test-bridge-token-with-enough-characters", "--runevault.pico-key=" + key};
  String id;
  DeviceApi.Authorization signed;
  try (var first = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test").run(arguments)) {
   var service = first.getBean(DeviceAuthorizationService.class);
   id = (String) service.createPending(new DeviceApi.Pending("slab-01", 50000L,
    "acct-999", "0-2-1-2-1")).getBody().get("tx_id");
   String text = "v1|slab-01|1|" + id + "|50000|acct-999|0-2-1-2-1";
   Mac mac = Mac.getInstance("HmacSHA256");
   mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
   signed = new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999",
    "0-2-1-2-1", HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))));
   assertEquals(200, service.authorize(signed).getStatusCode().value());
   assertEquals(id, first.getBean(com.runevault.transactions.TransactionService.class)
    .claimPicoAuthorized(id).id());
  }
  try (var second = new SpringApplicationBuilder(RunevaultApplication.class).profiles("dev", "test").run(arguments)) {
   assertEquals(409, second.getBean(DeviceAuthorizationService.class).authorize(signed).getStatusCode().value());
   assertEquals(1, second.getBean(DeviceStateRepository.class).findById("slab-01").orElseThrow().getLastCounter());
   assertEquals(Transaction.Status.SIMULATED,
    second.getBean(TransactionRepository.class).findById(id).orElseThrow().getStatus());
   assertEquals(2, second.getBean(AuditEventRepository.class).count());
   assertThrows(IllegalStateException.class, () -> second.getBean(
    com.runevault.transactions.TransactionService.class).claimPicoAuthorized(id));
  }
 }
}
