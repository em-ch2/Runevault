package com.runevault;

import com.runevault.ai.*;
import com.runevault.banking.*;
import com.runevault.transactions.*;
import com.runevault.glyphs.GlyphDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"runevault.review.score-threshold=0.7", "runevault.review.max-automatic-amount-minor=5000"})
@ActiveProfiles({"dev", "test"})
class TransactionProcessingTest {
    @Autowired TransactionRepository transactions;
    @Autowired TransactionProcessor processor;
    @Autowired TransactionApi api;
    @Autowired TransactionRecovery recovery;
    @Autowired FakeReceiptRepository receipts;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean AiAuditor auditor;
    @MockitoSpyBean FakeBankGateway bank;
    private AuditResult audit(AuditResult.RiskLevel risk, double score, boolean review, String source) {
        return new AuditResult("Fictional lore",risk,score,List.of(),"Explanation","Advisory only",review,source);
    }
    @BeforeEach void setup() {
        when(auditor.audit(any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return audit(AuditResult.RiskLevel.LOW,0.1,false,"gemini");
        });
    }
    private String verified(long amount) {
        String id = UUID.randomUUID().toString();
        var t = Transaction.pending(id,amount,"harbor","slab-01","0-1-2");
        t.verify(); transactions.saveAndFlush(t); return id;
    }
    private Transaction stored(String id) { return transactions.findById(id).orElseThrow(); }
    @Test void lowRiskHasOneDurableSimulatedReceiptAndVersions() {
        String id = verified(2500);
        var result = processor.process(id);
        assertEquals("SIMULATED", result.get("status"));
        assertEquals(result, processor.process(id));
        verify(bank,times(1)).transfer(id,2500,"USD","harbor");
        verify(auditor,times(1)).audit(any());
        var t = stored(id);
        assertNotNull(t.getAuditJson()); assertNotNull(t.getReceiptJson());
        assertEquals("gemini",t.getAuditSource());
        assertEquals(AuditPrompt.VERSION,t.getPromptVersion());
        assertEquals(GlyphDictionary.VERSION,t.getDictionaryVersion());
        assertNotNull(t.getExecutionStartedAt()); assertNotNull(t.getCompletedAt());
    }
    @Test void reviewHighRiskFallbackAndLimitsCannotReachBank() {
        for (var a : List.of(audit(AuditResult.RiskLevel.LOW,0.1,true,"gemini"),
                            audit(AuditResult.RiskLevel.HIGH,0.1,false,"gemini"),
                            AuditResult.unavailable(),audit(AuditResult.RiskLevel.LOW,0.7,false,"gemini"))) {
            when(auditor.audit(any())).thenReturn(a);
            String id = verified(2500);
            assertEquals(a.riskLevel() == AuditResult.RiskLevel.HIGH ? "REJECTED" : "APPROVAL_PENDING",processor.process(id).get("status"));
            processor.process(id);
            assertNotNull(stored(id).getReviewReason()); assertNull(stored(id).getReceiptJson());
        }
        when(auditor.audit(any())).thenReturn(audit(AuditResult.RiskLevel.LOW,0.69,false,"gemini"));
        assertEquals("APPROVAL_PENDING",processor.process(verified(5001)).get("status"));
        verifyNoInteractions(bank);
        assertEquals("SIMULATED",processor.process(verified(5000)).get("status"));
    }
    @Test void auditorExceptionRequiresReview() {
        when(auditor.audit(any())).thenThrow(new GeminiException("Unavailable"));
        String id = verified(2500);
        assertEquals("APPROVAL_PENDING",processor.process(id).get("status"));
        assertEquals("fallback",stored(id).getAuditSource());
        verifyNoInteractions(bank);
    }
    @Test void bankFailuresNeverClaimSuccessOrRetry() {
        String unknown = verified(2500);
        doAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(TransactionStatus.EXECUTING,stored(unknown).getStatus());
            throw new RuntimeException("Timeout after send");
        }).when(bank).transfer(unknown,2500,"USD","harbor");
        assertEquals("EXECUTION_UNKNOWN",processor.process(unknown).get("status"));
        assertEquals("EXECUTION_UNKNOWN",processor.process(unknown).get("status"));
        verify(bank,times(1)).transfer(unknown,2500,"USD","harbor");
        assertNull(stored(unknown).getCompletedAt());
        String rejected = verified(2500);
        doThrow(new BankRejectedException("Declined")).when(bank).transfer(rejected,2500,"USD","harbor");
        assertEquals("FAILED",processor.process(rejected).get("status"));
        processor.process(rejected);
        verify(bank,times(1)).transfer(rejected,2500,"USD","harbor");
    }
    @Test void concurrentProcessingClaimsAuditAndBankOnce() throws Exception {
        String id = verified(2500);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(auditor.audit(any())).thenAnswer(i -> {
            entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS));
            return audit(AuditResult.RiskLevel.LOW,0.1,false,"gemini");
        });
        try (var pool = Executors.newFixedThreadPool(4)) {
            var first = pool.submit(() -> processor.process(id));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try {
                for (int i=0;i<6;i++) assertEquals("VERIFIED",pool.submit(() -> processor.process(id)).get(5,TimeUnit.SECONDS).get("status"));
            } finally { release.countDown(); }
            assertEquals("SIMULATED",first.get(10,TimeUnit.SECONDS).get("status"));
        }
        verify(auditor,times(1)).audit(any());
        verify(bank,times(1)).transfer(id,2500,"USD","harbor");
    }
    @Test void pendingAndExpiredCannotBeProcessed() {
        String id=UUID.randomUUID().toString();
        transactions.saveAndFlush(Transaction.pending(id,2500,"harbor","pico-demo-1","AB"));
        assertEquals("PENDING",processor.process(id).get("status"));
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            var t=transactions.findForUpdate(id).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(t,"expiresAt",Instant.now().minusSeconds(1));
        });
        assertFalse(processor.verifyLegacy(id,new TransactionApi.Intent("pico-demo-1",2500,"harbor","AB")));
        assertEquals(TransactionStatus.EXPIRED,stored(id).getStatus());
        processor.process(id);
        verifyNoInteractions(auditor,bank);
    }
    @Test void legacyApprovalAlsoEnforcesReviewAndDoesNotBank() throws Exception {
        when(auditor.audit(any())).thenReturn(AuditResult.unavailable());
        var c=api.challenge(new TransactionApi.Intent("pico-demo-1",2500,"harbor","AB"));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("local-demo-only-change-before-deploy".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        var approval=new TransactionApi.Approval(c.challengeId(),HexFormat.of().formatHex(mac.doFinal(c.signingText().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertEquals("APPROVAL_PENDING",api.approve(approval).get("status"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->api.approve(approval));
        verifyNoInteractions(bank);
    }
    @Test void abandonedExecutionBecomesUnknownWithoutRetry() {
        String id=verified(2500);
        new TransactionTemplate(manager).executeWithoutResult(ignored -> {
            var t=transactions.findForUpdate(id).orElseThrow();
            t.transition(TransactionStatus.EXECUTING);
            org.springframework.test.util.ReflectionTestUtils.setField(t,"executionStartedAt",Instant.now().minusSeconds(600));
        });
        recovery.reconcile();
        assertEquals(TransactionStatus.EXECUTION_UNKNOWN,stored(id).getStatus());
        assertEquals("EXECUTION_UNKNOWN",processor.process(id).get("status"));
        verifyNoInteractions(auditor,bank);
    }
}
