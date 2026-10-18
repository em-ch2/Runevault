package com.runevault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maker-checker rule. When required, a transfer that passed the device and Gemini checks waits until a
 * second person with approve:transactions releases it with their normal access token; the requester never can.
 *
 * Escalation: with maker-checker on, a transfer whose only Gemini objection is a high risk score is not
 * blocked outright but sent to the same second-approver queue. Gemini being unavailable, answering
 * invalidly, or reading the glyphs with low confidence still blocks.
 */
@Validated
@ConfigurationProperties(prefix = "runevault.approval")
public record ApprovalPolicy(boolean required) {

 /** A high-risk Gemini decision goes to a second approver instead of being blocked, when maker-checker is on. */
 public boolean escalates(AuditEvent.Reason geminiDecision) {
  return required && geminiDecision == AuditEvent.Reason.GEMINI_HIGH_RISK;
 }

 /** Gemini decisions a second approver may release. */
 public boolean releasable(AuditEvent.Reason geminiDecision) {
  return geminiDecision == AuditEvent.Reason.GEMINI_APPROVED || escalates(geminiDecision);
 }
}
