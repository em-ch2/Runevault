package com.runevault.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AuditResultParserTest {
    static final String VALID = """
        {"lore":"Three hearts cross the sea.","riskLevel":"MEDIUM",
         "riskReasons":["Limited context"],"requiresHumanReview":true}
        """;
    private final AuditResultParser parser = new AuditResultParser();

    @Test void parsesTypedResultAndAssignsTrustedSource() {
        var result = parser.parse(VALID);
        assertEquals(AuditResult.RiskLevel.MEDIUM, result.riskLevel());
        assertEquals("Limited context", result.riskReasons().getFirst());
        assertTrue(result.requiresHumanReview());
        assertEquals("gemini", result.source());
        assertThrows(UnsupportedOperationException.class, () -> result.riskReasons().add("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "not json", "```json\n{}\n```"})
    void rejectsMissingOrNonObjectResults(String text) {
        assertThrows(GeminiException.class, () -> parser.parse(text));
    }

    @Test void rejectsInvalidFieldsAndTypes() {
        for (String invalid : new String[] {
            VALID.replace("MEDIUM", "UNKNOWN"),
            VALID.replace("true", "\"true\""),
            VALID.replace("[\"Limited context\"]", "[]"),
            VALID.replace("[\"Limited context\"]", "[1]"),
            VALID.replace("[\"Limited context\"]", "[\"a\",\"b\",\"c\",\"d\",\"e\"]"),
            VALID.replace("Three hearts cross the sea.", " "),
            VALID.replace("\"lore\":", "\"source\":\"mock\",\"lore\":"),
            VALID.replace("\"riskLevel\":", "\"riskLevel\":\"LOW\",\"riskLevel\":"),
            VALID + " {}"
        }) assertThrows(GeminiException.class, () -> parser.parse(invalid));
        assertThrows(GeminiException.class, () -> parser.parse(null));
    }
}
