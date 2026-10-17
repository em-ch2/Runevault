package com.runevault.ai;

import com.runevault.glyphs.GlyphDictionary;
import com.runevault.transactions.VerifiedTransaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnProperty(name = "runevault.ai.provider", havingValue = "gemini")
public class GeminiAiAuditor implements AiAuditor {
    private final GlyphDictionary dictionary;
    private final GeminiClient client;
    private final AuditResultParser parser;

    public GeminiAiAuditor(GlyphDictionary dictionary, GeminiClient client, AuditResultParser parser) {
        this.dictionary = dictionary;
        this.client = client;
        this.parser = parser;
    }

    @Override
    public AuditResult audit(VerifiedTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        List<String> facts = dictionary.decode(transaction.glyphs());
        try {
            return parser.parse(client.generate(AuditPrompt.build(transaction, facts)));
        } catch (GeminiException e) {
            return AuditResult.fallback(String.join(" ", facts));
        }
    }
}
