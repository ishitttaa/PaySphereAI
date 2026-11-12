package com.paysphere.payment.repository;

import com.paysphere.payment.entity.Payment;
import com.paysphere.payment.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

    List<Payment> findByBusinessOrderId(UUID businessOrderId);

    Optional<Payment> findByBusinessOrderIdAndStatus(UUID businessOrderId, PaymentStatus status);

    boolean existsByRazorpayPaymentId(String razorpayPaymentId);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatus status, Pageable pageable);

    long countByStatus(PaymentStatus status);
}
