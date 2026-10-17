package com.runevault.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;

@Component
public class AuditResultParser {
    private final com.runevault.transactions.ReviewPolicy policy;
    public AuditResultParser() { this(new com.runevault.transactions.ReviewPolicy(0.5, 100000)); }
    @org.springframework.beans.factory.annotation.Autowired
    public AuditResultParser(com.runevault.transactions.ReviewPolicy policy) { this.policy = policy; }
    private static final int MAX_FLAGS = 6;
    private static final Set<String> FIELDS = Set.of(
        "lore_translation", "risk_level", "risk_score",
        "risk_flags", "risk_explanation", "security_message");
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public AuditResult parse(String text) {
        try {
            if (text == null || text.isBlank()) throw invalid();
            JsonNode root = mapper.readTree(text);
            if (!root.isObject() || root.size() != FIELDS.size()) throw invalid();
            var names = root.fieldNames();
            while (names.hasNext()) {
                if (!FIELDS.contains(names.next())) throw invalid();
            }
            String lore = requiredText(root.path("lore_translation"));
            AuditResult.RiskLevel risk = AuditResult.RiskLevel.valueOf(requiredText(root.path("risk_level")));
            JsonNode scoreNode = root.path("risk_score");
            if (!scoreNode.isNumber()) throw invalid();
            double score = scoreNode.doubleValue();
            if (Double.isNaN(score) || score < 0.0 || score > 1.0) throw invalid();
            JsonNode flags = root.path("risk_flags");
            if (!flags.isArray() || flags.size() > MAX_FLAGS) throw invalid();
            var parsedFlags = new ArrayList<String>();
            for (JsonNode flag : flags) parsedFlags.add(requiredText(flag));
            String explanation = requiredText(root.path("risk_explanation"));
            String message = requiredText(root.path("security_message"));
            // Deterministic backend rule: the model cannot clear its own review flag.
            boolean review = policy.requiresReview(new AuditResult(lore, risk, score, parsedFlags, explanation, message, false, "gemini"));
            return new AuditResult(lore, risk, score, parsedFlags, explanation, message, review, "gemini");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Do not expose model output or transaction data in errors.
            throw invalid();
        }
    }

    private static String requiredText(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank()) throw invalid();
        return node.textValue();
    }

    private static GeminiException invalid() {
        return new GeminiException("Gemini returned an invalid audit result");
    }
}