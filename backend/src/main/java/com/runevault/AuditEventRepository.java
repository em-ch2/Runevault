package com.runevault;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
 // The most recent fixed reason recorded for a transaction, e.g. why a signed approval was refused.
 Optional<AuditEvent> findFirstByTransactionIdAndEventTypeOrderByIdDesc(String transactionId, AuditEvent.Type type);
}
