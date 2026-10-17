package com.runevault.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AuditResultParserTest {
    static final String VALID = """
        {"lore_translation":"Three hearts cross the sea.","risk_level":"MEDIUM","risk_score":0.45,
         "risk_flags":["limited_context"],"risk_explanation":"Only one transaction was visible.",
         "security_message":"Hardware approval verified; review advised."}
        """;
    private final AuditResultParser parser = new AuditResultParser();

    private static String json(String level, String score) {
        return """
            {"lore_translation":"x","risk_level":"%s","risk_score":%s,"risk_flags":[],
             "risk_explanation":"e","security_message":"m"}
            """.formatted(level, score);
    }

    @Test void parsesContractResultAndAssignsTrustedFields() {
        var result = parser.parse(VALID);
        assertEquals("Three hearts cross the sea.", result.loreTranslation());
        assertEquals(AuditResult.RiskLevel.MEDIUM, result.riskLevel());
        assertEquals(0.45, result.riskScore(), 1e-9);
        assertEquals("limited_context", result.riskFlags().getFirst());
        assertEquals("Only one transaction was visible.", result.riskExplanation());
        assertEquals("Hardware approval verified; review advised.", result.securityMessage());
        assertTrue(result.requiresHumanReview());
        assertEquals("gemini", result.source());
        assertThrows(UnsupportedOperationException.class, () -> result.riskFlags().add("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "not json", "```json\n{}\n```"})
    void rejectsMissingOrNonObjectResults(String text) {
        assertThrows(GeminiException.class, () -> parser.parse(text));
    }

    @Test void rejectsInvalidFieldsAndTypes() {
        for (String invalid : new String[] {
            VALID.replace("MEDIUM", "UNKNOWN"),
            VALID.replace("0.45", "\"0.45\""),
            VALID.replace("0.45", "1.5"),
            VALID.replace("0.45", "-0.1"),
            VALID.replace("[\"limited_context\"]", "[1]"),
            VALID.replace("[\"limited_context\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]"),
            VALID.replace("Three hearts cross the sea.", " "),
            VALID.replace("Hardware approval verified; review advised.", " "),
            VALID.replace("\"lore_translation\":", "\"source\":\"mock\",\"lore_translation\":"),
            VALID.replace("\"risk_level\":", "\"risk_level\":\"LOW\",\"risk_level\":"),
            VALID.replace("\"risk_score\":0.45,", ""),
            VALID + " {}"
        }) assertThrows(GeminiException.class, () -> parser.parse(invalid), invalid);
        assertThrows(GeminiException.class, () -> parser.parse(null));
    }

    @Test void modelCannotClearItsOwnReviewFlag() {
        assertFalse(parser.parse(json("LOW", "0.1")).requiresHumanReview());
        assertTrue(parser.parse(json("LOW", "0.9")).requiresHumanReview());
        assertTrue(parser.parse(json("MEDIUM", "0.0")).requiresHumanReview());
        assertTrue(parser.parse(json("HIGH", "0.0")).requiresHumanReview());
    }
}