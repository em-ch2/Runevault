package com.runevault.ai;

import java.util.List;

public record AuditResult(
    String lore,
    RiskLevel riskLevel,
    List<String> riskReasons,
    boolean requiresHumanReview,
    String source
) {
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public AuditResult {
        riskReasons = List.copyOf(riskReasons);
    }
}