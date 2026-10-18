package com.runevault;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maker-checker and step-up rules. When required, a transfer that passed the device and Gemini checks
 * waits until a second person with approve:transactions releases it; the requester never can.
 * Releasing a transfer of stepUpAmountMinor or more also needs an MFA-backed access token.
 *
 * Escalation: with maker-checker on, a transfer whose only Gemini objection is a high risk score is not
 * blocked outright but sent to the same second-approver queue, and releasing it always needs step-up MFA.
 * Gemini being unavailable, answering invalidly, or reading the glyphs with low confidence still blocks.
 */
@Validated
@ConfigurationProperties(prefix = "runevault.approval")
public record ApprovalPolicy(boolean required, @Min(1) @Max(100000000) long stepUpAmountMinor) {

 public boolean requiresStepUp(long amountMinor) { return amountMinor >= stepUpAmountMinor; }

 /** A high-risk Gemini decision goes to a second approver instead of being blocked, when maker-checker is on. */
 public boolean escalates(AuditEvent.Reason geminiDecision) {
  return required && geminiDecision == AuditEvent.Reason.GEMINI_HIGH_RISK;
 }

 /** Gemini decisions a second approver may release. */
 public boolean releasable(AuditEvent.Reason geminiDecision) {
  return geminiDecision == AuditEvent.Reason.GEMINI_APPROVED || escalates(geminiDecision);
 }

 /** Step-up for a release: large amounts, and every transfer escalated for high risk. */
 public boolean requiresStepUp(long amountMinor, AuditEvent.Reason geminiDecision) {
  return requiresStepUp(amountMinor) || escalates(geminiDecision);
 }
}
