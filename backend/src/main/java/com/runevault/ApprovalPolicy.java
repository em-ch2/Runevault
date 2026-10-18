package com.runevault;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maker-checker and step-up rules. When required, a transfer that passed the device and Gemini checks
 * waits until a second person with approve:transactions releases it; the requester never can.
 * Releasing a transfer of stepUpAmountMinor or more also needs an MFA-backed access token.
 */
@Validated
@ConfigurationProperties(prefix = "runevault.approval")
public record ApprovalPolicy(boolean required, @Min(1) @Max(100000000) long stepUpAmountMinor) {

 public boolean requiresStepUp(long amountMinor) { return amountMinor >= stepUpAmountMinor; }
}
