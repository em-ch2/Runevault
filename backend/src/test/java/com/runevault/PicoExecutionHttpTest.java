package com.runevault;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=pico-execution-test-key",
 "runevault.pico-device-id=slab-01",
 "runevault.auto-execute=true",
 "spring.datasource.url=jdbc:h2:mem:pico-execution;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class PicoExecutionHttpTest {
 private static final String TOKEN = "test-bridge-token-with-enough-characters";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired TransactionRepository transactions;

 @Test void verifiedPicoApprovalExecutesTransferOnce() throws Exception {
  var created = mvc.perform(post("/api/device/transactions").header("X-Bridge-Token", TOKEN)
   .contentType("application/json").content(mapper.writeValueAsString(
    Map.of("device", "slab-01", "amount", 50000, "dest", "acct-999", "glyphs", "0-1-2"))))
   .andExpect(status().isCreated()).andReturn();
  String id = mapper.readTree(created.getResponse().getContentAsString()).get("tx_id").asText();

  String text = "v1|slab-01|1|" + id + "|50000|acct-999|0-1-2";
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec("pico-execution-test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  String sig = HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  String body = mapper.writeValueAsString(new DeviceApi.Authorization(1, "slab-01", 1L, id, 50000L, "acct-999", "0-1-2", sig));

  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN).contentType("application/json").content(body))
   .andExpect(status().isOk())
   .andExpect(jsonPath("status").value("VERIFIED"))
   .andExpect(jsonPath("execution.status").value("EXECUTED"))
   .andExpect(jsonPath("execution.receipt.receiptId").value("fake-" + id))
   // No Gemini key in tests, so the advisory audit fails safely without blocking the transfer.
   .andExpect(jsonPath("execution.analysis.error").value("Lore unavailable"));
  var saved = transactions.findById(id).orElseThrow();
  assertEquals(Transaction.Status.EXECUTED, saved.getStatus());
  assertEquals("fake-" + id, saved.getBankReceiptId());

  // Replaying the same signed approval is rejected and never transfers again.
  mvc.perform(post("/api/device/authorize").header("X-Bridge-Token", TOKEN).contentType("application/json").content(body))
   .andExpect(status().isConflict());
 }
}
