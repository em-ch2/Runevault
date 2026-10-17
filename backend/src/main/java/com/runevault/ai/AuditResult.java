package com.runevault.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Advisory AI output. The first six fields are the Gemini output contract;
 * requiresHumanReview and source are assigned by the backend, never by the model.
 */
public record AuditResult(
    @JsonProperty("lore_translation") String loreTranslation,
    @JsonProperty("risk_level") RiskLevel riskLevel,
    @JsonProperty("risk_score") double riskScore,
    @JsonProperty("risk_flags") List<String> riskFlags,
    @JsonProperty("risk_explanation") String riskExplanation,
    @JsonProperty("security_message") String securityMessage,
    @JsonProperty("requires_human_review") boolean requiresHumanReview,
    @JsonProperty("source") String source
) {
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public AuditResult {
        riskFlags = List.copyOf(riskFlags);
    }

    /** Used when the AI cannot produce a valid assessment. Always requires review. */
    public static AuditResult fallback(String lore) {
        return new AuditResult(
            lore,
            RiskLevel.MEDIUM,
            0.5,
            List.of("ai_unavailable"),
            "AI assessment unavailable; human review is required.",
            "Hardware authorization was verified, but the AI risk check did not complete. Please review this transfer manually.",
            true,
            "fallback"
        );
    }

    public static AuditResult unavailable() {
        return fallback("The stone stays silent.");
    }
}