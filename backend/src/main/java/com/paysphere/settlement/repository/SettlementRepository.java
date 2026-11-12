package com.paysphere.settlement.repository;

import com.paysphere.settlement.entity.Settlement;
import com.paysphere.settlement.entity.SettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByRazorpaySettlementId(String razorpaySettlementId);
    boolean existsByRazorpaySettlementId(String razorpaySettlementId);
    Page<Settlement> findByStatusOrderByCreatedAtDesc(SettlementStatus status, Pageable pageable);
    Page<Settlement> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
