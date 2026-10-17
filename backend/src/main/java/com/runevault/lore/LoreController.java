package com.runevault.lore;

import com.fasterxml.jackson.databind.JsonNode;
import com.runevault.TransactionRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/lore")
public class LoreController {
 // Placeholder meanings: replace with your real glyph lore.
 private static final String DICTIONARY = """
   0 = the Ember (beginnings, fire, a forge lit)
   1 = the Tide (trade, exchange, journeys)
   2 = the Seal (oaths, protection, closed vaults)
   """;

 private final TransactionRepository transactions;
 private final LoreAuditService lore;

 public LoreController(TransactionRepository transactions, LoreAuditService lore) {
  this.transactions = transactions;
  this.lore = lore;
 }

 @GetMapping("/{id}")
 public Map<String, Object> lore(@PathVariable String id) {
  if (!id.matches("[A-Za-z0-9_-]{1,36}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction ID");
  var tx = transactions.findById(id)
   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
  try {
   JsonNode result = lore.audit(tx.getExpectedGlyphs(), DICTIONARY, tx.getAmountMinor(), tx.getDestination());
   return Map.of("tx_id", id, "ai_generated", true, "result", result);
  } catch (Exception e) {
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Lore unavailable");
  }
 }
}