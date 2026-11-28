package com.paysphere.ledger.repository;

import com.paysphere.ledger.entity.LedgerTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
    Page<LedgerTransaction> findAllByOrderByPostedAtDesc(Pageable pageable);
    Page<LedgerTransaction> findBySourceTypeOrderByPostedAtDesc(String sourceType, Pageable pageable);
        /** Transactions that come from one merchant's payments and refunds. Settlements have no merchant. */
    @Query("select t from LedgerTransaction t where "
            + "(t.sourceType = 'PAYMENT' and t.sourceId in "
            + "    (select p.id from Payment p where p.businessOrder.merchantId = :merchantId)) "
            + "or (t.sourceType in ('REFUND', 'ADJUSTMENT') and t.sourceId in "
            + "    (select r.id from Refund r where r.businessOrder.merchantId = :merchantId))")
    Page<LedgerTransaction> findForMerchant(@Param("merchantId") UUID merchantId, Pageable pageable);

    @Query("select t from LedgerTransaction t where t.sourceType = :sourceType and ("
            + "(t.sourceType = 'PAYMENT' and t.sourceId in "
            + "    (select p.id from Payment p where p.businessOrder.merchantId = :merchantId)) "
            + "or (t.sourceType in ('REFUND', 'ADJUSTMENT') and t.sourceId in "
            + "    (select r.id from Refund r where r.businessOrder.merchantId = :merchantId)))")
    Page<LedgerTransaction> findForMerchantAndSourceType(@Param("merchantId") UUID merchantId,
                                                         @Param("sourceType") String sourceType,
                                                         Pageable pageable);
}
