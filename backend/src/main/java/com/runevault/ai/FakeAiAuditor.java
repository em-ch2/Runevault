package com.runevault.ai;

import com.runevault.glyphs.GlyphDictionary;
import com.runevault.transactions.VerifiedTransaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnProperty(
    name = "runevault.ai.provider",
    havingValue = "mock",
    matchIfMissing = true
)

public class FakeAiAuditor implements AiAuditor {
    private final GlyphDictionary glyphDictionary;

    public FakeAiAuditor(GlyphDictionary glyphDictionary) {
        this.glyphDictionary = glyphDictionary;
    }

    @Override
    public AuditResult audit(VerifiedTransaction transaction) {
        Objects.requireNonNull(transaction, "Transaction is required");

        List<String> facts =
            glyphDictionary.decode(transaction.glyphs());

        return new AuditResult(
            String.join(" ", facts),
            AuditResult.RiskLevel.LOW,
            List.of("Offline simulation; no real risk assessment performed."),
            false,
            "mock"
        );
    }
}
