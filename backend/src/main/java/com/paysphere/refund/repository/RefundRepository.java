package com.paysphere.refund.repository;

import com.paysphere.refund.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentId(UUID paymentId);

    List<Refund> findByBusinessOrderId(UUID businessOrderId);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByRazorpayRefundId(String razorpayRefundId);
    /** Only the payment id: loading the Refund entity here would hide fresh data after we lock the payment. */
    @Query("select r.payment.id from Refund r where r.id = :refundId")
    Optional<UUID> findPaymentIdByRefundId(@Param("refundId") UUID refundId);

    @Query("select r.id from Refund r "
            + "where r.status = com.paysphere.refund.entity.RefundStatus.PENDING "
            + "and r.createdAt < :cutoff order by r.createdAt")
    List<UUID> findStalePendingIds(@Param("cutoff") Instant cutoff);

    /** Total of refunds the gateway has really processed. Summed in the database. */
    @Query("select coalesce(sum(r.amountPaise), 0) from Refund r "
            + "where r.status = com.paysphere.refund.entity.RefundStatus.PROCESSED")
    Long sumProcessedAmountPaise();
}