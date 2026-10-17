package com.runevault.ai;

import com.runevault.transactions.VerifiedTransaction;

public interface AiAuditor {
    AuditResult audit(VerifiedTransaction transaction);
}