package com.runevault;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "runevault.transaction-policy")
public record TransactionPolicy(
  @Min(1) @Max(100000000) long maxAmountMinor,
  @NotEmpty List<@Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> allowedDestinations,
  @Min(1) int maxIncorrectGlyphAttempts) {

 public boolean allowsAmount(long amountMinor) { return amountMinor <= maxAmountMinor; }
 public boolean allowsDestination(String destination) { return allowedDestinations.contains(destination); }
}
