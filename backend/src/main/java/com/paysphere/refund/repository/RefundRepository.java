package com.paysphere.refund.repository;

import com.paysphere.refund.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentId(UUID paymentId);

    List<Refund> findByBusinessOrderId(UUID businessOrderId);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByRazorpayRefundId(String razorpayRefundId);
}
