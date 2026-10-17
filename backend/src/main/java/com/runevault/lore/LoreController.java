package com.runevault.lore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runevault.TransactionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/lore")
public class LoreController {
 private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoreController.class);

 private final TransactionRepository transactions;
 private final LoreAuditService lore;
 private final ObjectMapper mapper;

 public LoreController(TransactionRepository transactions, LoreAuditService lore, ObjectMapper mapper) {
  this.transactions = transactions;
  this.lore = lore;
  this.mapper = mapper;
 }

 @GetMapping("/{id}")
 public Map<String, Object> lore(@PathVariable String id) {
  if (!id.matches("[A-Za-z0-9_-]{1,36}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction ID");
  var tx = transactions.findById(id)
   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
  try {
   if (tx.getAuditJson() != null) {
    return Map.of("tx_id", id, "ai_generated", true, "cached", true,
      "result", mapper.readTree(tx.getAuditJson()));
   }
   JsonNode result = lore.audit(tx.getExpectedGlyphs(), LoreAuditService.DICTIONARY, tx.getAmountMinor(), tx.getDestination());
   transactions.storeAudit(id, result.toString(), "gemini", LoreAuditService.PROMPT_VERSION, Instant.now());
   return Map.of("tx_id", id, "ai_generated", true, "cached", false, "result", result);
  } catch (Exception e) {
   log.error("Lore generation failed for {}", id, e);
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Lore unavailable");
  }
 }
}