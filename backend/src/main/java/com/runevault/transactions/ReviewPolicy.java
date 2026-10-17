package com.runevault.transactions;

import com.runevault.ai.AuditResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewPolicy {
    private final double scoreThreshold;
    private final long maxAutomaticAmount;
    public ReviewPolicy(@Value("${runevault.review.score-threshold:0.5}") double scoreThreshold,
                        @Value("${runevault.review.max-automatic-amount-minor:100000}") long maxAutomaticAmount) {
        if (!Double.isFinite(scoreThreshold) || scoreThreshold < 0 || scoreThreshold > 1 || maxAutomaticAmount < 0)
            throw new IllegalArgumentException("Invalid review policy configuration");
        this.scoreThreshold = scoreThreshold;
        this.maxAutomaticAmount = maxAutomaticAmount;
    }
    public boolean requiresReview(AuditResult result) {
        return result.requiresHumanReview() || result.riskLevel() != AuditResult.RiskLevel.LOW
            || !Double.isFinite(result.riskScore()) || result.riskScore() >= scoreThreshold
            || !("gemini".equals(result.source()) || "mock".equals(result.source()));
    }
    public record Decision(TransactionStatus status, String reason) {}
    public Decision decide(AuditResult result, long amount) {
        if (result.riskLevel() == AuditResult.RiskLevel.HIGH)
            return new Decision(TransactionStatus.REJECTED, "HIGH risk is blocked by backend policy");
        if (requiresReview(result))
            return new Decision(TransactionStatus.APPROVAL_PENDING, "Audit or unavailable AI requires human review");
        if (amount > maxAutomaticAmount)
            return new Decision(TransactionStatus.APPROVAL_PENDING, "Amount exceeds automatic transfer limit");
        return new Decision(TransactionStatus.APPROVED, null);
    }
}
