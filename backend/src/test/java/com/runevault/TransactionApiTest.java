package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessResourceFailureException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionApiTest {
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionProcessor processor = mock(TransactionProcessor.class);
    private final TransactionApi api = new TransactionApi("test-key", "local", repository, processor);
    private TransactionApi.Challenge challenge() {
        return api.challenge(new TransactionApi.Intent("pico-demo-1",2500,"harbor","ABCD"));
    }
    private String sign(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void acceptsOnceAndRejectsReplay() throws Exception {
        var c = challenge();
        var saved = org.mockito.ArgumentCaptor.forClass(Transaction.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(com.runevault.transactions.TransactionStatus.PENDING, saved.getValue().getStatus());
        when(processor.verifyLegacy(c.challengeId(), c.intent())).thenReturn(true);
        when(processor.process(c.challengeId())).thenReturn(Map.of("status", "SIMULATED"));
        var approval = new TransactionApi.Approval(c.challengeId(), sign(c.signingText()));
        assertEquals("SIMULATED", api.approve(approval).get("status"));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> api.approve(approval)).getStatusCode().value());
        var order = inOrder(processor);
        order.verify(processor).verifyLegacy(c.challengeId(), c.intent());
        order.verify(processor).process(c.challengeId());
        verifyNoMoreInteractions(processor);
    }
    @Test void invalidSignatureCannotReachProcessor() throws Exception {
        var c = challenge();
        var bad = new TransactionApi.Approval(c.challengeId(), sign(c.signingText().replace("2500", "2501")));
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> api.approve(bad)).getStatusCode().value());
        verifyNoInteractions(processor);
        when(processor.verifyLegacy(c.challengeId(), c.intent())).thenReturn(true);
        api.approve(new TransactionApi.Approval(c.challengeId(), sign(c.signingText())));
        verify(processor).process(c.challengeId());
    }
    @Test void failedVerificationSaveCanRetry() throws Exception {
        var c = challenge();
        var approval = new TransactionApi.Approval(c.challengeId(), sign(c.signingText()));
        when(processor.verifyLegacy(c.challengeId(), c.intent())).thenThrow(new DataAccessResourceFailureException("Unavailable")).thenReturn(true);
        assertThrows(DataAccessResourceFailureException.class, () -> api.approve(approval));
        verify(processor, never()).process(anyString());
        api.approve(approval);
        verify(processor).process(c.challengeId());
    }
    @Test void signingProtocolUnchanged() {
        var c = challenge();
        assertEquals(String.join("\n", "runevault-v1", c.challengeId(), "pico-demo-1", "2500", "USD", "harbor", "ABCD", Long.toString(c.expiresAt())), c.signingText());
    }
    @Test void productionModeRefused() {
        assertThrows(IllegalStateException.class, () -> new TransactionApi("key", "production", repository, processor));
    }
}
