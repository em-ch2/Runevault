package com.runevault.ai;

import com.runevault.glyphs.GlyphDictionary;
import com.runevault.transactions.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiAiAuditorTest {
    private final GeminiClient client = mock(GeminiClient.class);
    private final GeminiAiAuditor auditor = new GeminiAiAuditor(new GlyphDictionary(), client, new AuditResultParser());

    @Test void buildsPromptAndReturnsParsedResult() {
        when(client.generate(anyString())).thenReturn(AuditResultParserTest.VALID);
        var transaction = new TransactionService().createVerifiedTransaction("tx-1", "pico-demo-1", 2500, "harbor", "AB");
        var result = auditor.audit(transaction);
        assertEquals("gemini", result.source());
        assertEquals(AuditResult.RiskLevel.MEDIUM, result.riskLevel());
        verify(client).generate(AuditPrompt.build(transaction, new GlyphDictionary().decode("AB")));
    }

    @Test void invalidOutputAndClientFailureRequireReview() {
        when(client.generate(anyString())).thenReturn("{}").thenThrow(new GeminiException("Unavailable"));
        var transaction = new TransactionService().createVerifiedTransaction("tx-1", "pico-demo-1", 2500, "harbor", "AB");
        for (int i = 0; i < 2; i++) {
            var result = auditor.audit(transaction);
            assertEquals("fallback", result.source());
            assertTrue(result.requiresHumanReview());
            assertEquals(AuditResult.RiskLevel.MEDIUM, result.riskLevel());
            assertEquals(String.join(" ", new GlyphDictionary().decode("AB")), result.loreTranslation());
        }
    }

    @Test void selectsTheRightAuditor() {
    var base = new ApplicationContextRunner()
        .withUserConfiguration(FakeAiAuditor.class, GeminiAiAuditor.class,
            AuditResultParser.class, GlyphDictionary.class, com.runevault.transactions.ReviewPolicy.class)
        .withBean(GeminiClient.class, () -> client)
        .withPropertyValues("runevault.mode=local");

    base.run(c -> {
        assertEquals(1, c.getBeansOfType(AiAuditor.class).size());
        assertInstanceOf(FakeAiAuditor.class, c.getBean(AiAuditor.class));
    });
    base.withPropertyValues("runevault.ai.provider=gemini").run(c ->
        assertInstanceOf(GeminiAiAuditor.class, c.getBean(AiAuditor.class)));
    }
}
