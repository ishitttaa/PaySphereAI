package com.paysphere.payment.repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("select p.id from Payment p where p.razorpayPaymentId = :razorpayPaymentId")
    Optional<UUID> findIdByRazorpayPaymentId(@Param("razorpayPaymentId") String razorpayPaymentId);

    long countByStatus(PaymentStatus status);

    /** Money that was ever captured: CAPTURED plus fully REFUNDED payments. Summed in the database. */
    @Query("select coalesce(sum(p.amountPaise), 0) from Payment p "
            + "where p.status in (com.paysphere.payment.entity.PaymentStatus.CAPTURED, "
            + "                   com.paysphere.payment.entity.PaymentStatus.REFUNDED)")
    Long sumCapturedAmountPaise();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}