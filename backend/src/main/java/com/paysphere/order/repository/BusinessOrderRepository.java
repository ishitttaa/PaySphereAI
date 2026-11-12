package com.paysphere.order.repository;

import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessOrderRepository extends JpaRepository<BusinessOrder, UUID> {

    Optional<BusinessOrder> findByOrderReference(String orderReference);

    Optional<BusinessOrder> findByRazorpayOrderId(String razorpayOrderId);

    Page<BusinessOrder> findByStatusOrderByCreatedAtDesc(BusinessOrderStatus status, Pageable pageable);

    Page<BusinessOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByOrderReference(String orderReference);

    long countByStatus(BusinessOrderStatus status);
}
