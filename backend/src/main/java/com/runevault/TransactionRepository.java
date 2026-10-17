package com.runevault;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select t from Transaction t where t.transactionId = :id")
 java.util.Optional<Transaction> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
