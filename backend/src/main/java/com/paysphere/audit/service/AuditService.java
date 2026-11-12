package com.paysphere.audit.service;

import com.paysphere.audit.entity.AuditLog;
import com.paysphere.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * AuditService — records significant actions for compliance and security.
 *
 * Uses @Async so audit logging never blocks the main request thread.
 * Uses Propagation.REQUIRES_NEW so audit logs are committed even if the
 * main transaction rolls back (we want to audit failures too).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Log an action. Called from services after significant events.
     * Fire-and-forget: failures here don't affect the main operation.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String entityType, UUID entityId,
                    String description, UUID actorUserId, String actorEmail) {
        try {
            AuditLog entry = AuditLog.builder()
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .description(description)
                    .actorUserId(actorUserId)
                    .actorEmail(actorEmail)
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Audit failures must NEVER break the main flow
            log.error("Audit log write failed for action={}: {}", action, e.getMessage());
        }
    }

    /** Log a system action (no user context). */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSystem(String action, String entityType, UUID entityId, String description) {
        log(action, entityType, entityId, description, null, "SYSTEM");
    }

    /** Retrieve audit logs for a specific entity. */
    @Transactional(readOnly = true)
    public Page<AuditLog> getLogsForEntity(String entityType, UUID entityId, Pageable pageable) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                entityType, entityId, pageable);
    }

    /** Retrieve all audit logs. */
    @Transactional(readOnly = true)
    public Page<AuditLog> getAllLogs(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
