package com.paysphere.ledger.repository;

import com.paysphere.ledger.entity.LedgerTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
    Page<LedgerTransaction> findAllByOrderByPostedAtDesc(Pageable pageable);
    Page<LedgerTransaction> findBySourceTypeOrderByPostedAtDesc(String sourceType, Pageable pageable);
}
