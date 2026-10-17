package com.runevault.transactions;

public enum TransactionStatus {
    RECEIVED,
    VERIFIED,
    AUDITED,
    APPROVAL_PENDING,
    APPROVED,
    EXECUTING,
    REJECTED,
    EXPIRED,
    FAILED,
    EXECUTION_UNKNOWN
}