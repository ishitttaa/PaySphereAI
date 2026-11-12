package com.paysphere.risk.repository;

import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.entity.RiskFlagStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RiskFlagRepository extends JpaRepository<RiskFlag, UUID> {
    List<RiskFlag> findByEntityTypeAndEntityId(String entityType, UUID entityId);
    Page<RiskFlag> findByStatusOrderByCreatedAtDesc(RiskFlagStatus status, Pageable pageable);
    Page<RiskFlag> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByStatus(RiskFlagStatus status);
}
