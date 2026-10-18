package com.runevault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maker-checker rule. When required, a transfer that passed the device and Gemini checks waits until a
 * second person with approve:transactions releases it with their normal access token; the requester never can.
 */
@Validated
@ConfigurationProperties(prefix = "runevault.approval")
public record ApprovalPolicy(boolean required) {}
