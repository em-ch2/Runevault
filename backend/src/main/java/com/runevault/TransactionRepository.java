package com.runevault;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

 Page<Transaction> findByRequesterSubject(String requesterSubject, Pageable pageable);

 @Query("select t.deviceId from Transaction t where t.transactionId = :id")
 Optional<String> findAssignedDeviceId(@Param("id") String id);

 // Oldest live pending assignment first, so the bridge serves requests in order.
 Optional<Transaction> findFirstByDeviceIdAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
   String deviceId, Transaction.Status status, Instant now);

 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select t from Transaction t where t.transactionId = :id")
 Optional<Transaction> findForUpdate(@Param("id") String id);

 @Modifying
 @Transactional
 @Query("update Transaction t set t.auditJson = :json, t.auditSource = :source, "
   + "t.promptVersion = :prompt, t.auditedAt = :at "
   + "where t.transactionId = :id and t.auditJson is null")
 int storeAudit(@Param("id") String id, @Param("json") String json, @Param("source") String source,
   @Param("prompt") String prompt, @Param("at") Instant at);
}
