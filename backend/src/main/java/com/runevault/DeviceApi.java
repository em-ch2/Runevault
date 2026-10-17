package com.runevault;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
public class DeviceApi {
 private final DeviceAuthorizationService service;
 public DeviceApi(DeviceAuthorizationService service) { this.service = service; }

 public record Authorization(
  @NotNull @Min(1) @Max(1) Integer v,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String device,
  @NotNull @Min(0) Long counter,
  @JsonProperty("tx_id") @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String txId,
  @NotNull @Min(1) @Max(100000000) Long amount,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String dest,
  @NotNull @Pattern(regexp = "[0-2](-[0-2]){0,63}") String glyphs,
  @NotNull @Pattern(regexp = "[0-9a-fA-F]{64}") String sig) {}

 public record Pending(
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String device,
  @NotNull @Min(1) @Max(100000000) Long amount,
  @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String dest,
  @NotNull @Pattern(regexp = "[0-2](-[0-2]){0,63}") String glyphs) {}

 @PostMapping("/transactions")
 public ResponseEntity<Map<String, Object>> pending(@Valid @RequestBody Pending request) {
  return service.createPending(request);
 }

 @PostMapping("/authorize")
 public ResponseEntity<Map<String, Object>> authorize(@Valid @RequestBody Authorization request) {
  return service.authorize(request);
 }
}
