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
    private static final Set<String> FIELDS = Set.of(
        "lore", "riskLevel", "riskReasons", "requiresHumanReview");
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
            String lore = requiredText(root.path("lore"));
            AuditResult.RiskLevel risk = AuditResult.RiskLevel.valueOf(requiredText(root.path("riskLevel")));
            JsonNode reasons = root.path("riskReasons");
            if (!reasons.isArray() || reasons.size() < 1 || reasons.size() > 4) throw invalid();
            var parsedReasons = new ArrayList<String>();
            for (JsonNode reason : reasons) parsedReasons.add(requiredText(reason));
            JsonNode review = root.path("requiresHumanReview");
            if (!review.isBoolean()) throw invalid();
            return new AuditResult(lore, risk, parsedReasons, review.booleanValue(), "gemini");
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
