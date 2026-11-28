package com.paysphere.order.repository;

import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Repository
public interface BusinessOrderRepository extends JpaRepository<BusinessOrder, UUID> {
    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, BusinessOrderStatus status);
    Optional<BusinessOrder> findByOrderReference(String orderReference);

    Optional<BusinessOrder> findByRazorpayOrderId(String razorpayOrderId);

    Page<BusinessOrder> findByStatusOrderByCreatedAtDesc(BusinessOrderStatus status, Pageable pageable);

    Page<BusinessOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<BusinessOrder> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<BusinessOrder> findByMerchantIdAndStatusOrderByCreatedAtDesc(
        UUID merchantId, BusinessOrderStatus status, Pageable pageable);

    boolean existsByOrderReference(String orderReference);

    long countByStatus(BusinessOrderStatus status);
        /**
     * Expires unpaid orders in ONE atomic statement. Orders that already have any payment
     * attached are never touched.
     */
    @Modifying(clearAutomatically = true)
    @Query("update BusinessOrder o "
            + "set o.status = com.paysphere.order.entity.BusinessOrderStatus.PAYMENT_EXPIRED, "
            + "    o.updatedAt = :now "
            + "where o.status in (com.paysphere.order.entity.BusinessOrderStatus.PENDING, "
            + "                   com.paysphere.order.entity.BusinessOrderStatus.PAYMENT_INITIATED) "
            + "and o.expiresAt is not null and o.expiresAt < :cutoff "
            + "and not exists (select p.id from Payment p where p.businessOrder.id = o.id)")
    int expireStaleOrders(@Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
