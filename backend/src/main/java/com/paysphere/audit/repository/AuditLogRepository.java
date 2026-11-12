package com.paysphere.audit.repository;

import com.paysphere.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, UUID entityId, Pageable pageable);
    Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
