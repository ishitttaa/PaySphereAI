package com.paysphere.audit.service;

import com.paysphere.audit.entity.AuditLog;
import com.paysphere.audit.repository.AuditLogRepository;
import com.paysphere.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * AuditService: records significant actions for compliance and security.
 *
 * Money events use record(): it runs INSIDE the caller's transaction, so the audit row and the
 * business change succeed or fail together. It also notes who did it (the logged-in user's email,
 * else the label the caller gave), plus the caller's IP address and browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Record a money-related event INSIDE the caller's transaction.
     * MANDATORY: fails loudly if there is no transaction.
     *
     * The actor is the logged-in user's email when there is one; otherwise actorLabel
     * (for example CHECKOUT or WEBHOOK, for callers who are not logged in).
     * actor_user_id is deliberately left empty: it is a foreign key, and an audit row must
     * never be able to block a payment because a user was deleted a minute ago.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, UUID entityId,
                       String description, String actorLabel) {
        HttpServletRequest request = currentRequest();
        auditLogRepository.save(AuditLog.builder()
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .description(truncate(description, 500))
                .actorEmail(truncate(CurrentUser.email().orElse(actorLabel), 255))
                .ipAddress(request == null ? null : truncate(request.getRemoteAddr(), 45))
                .userAgent(request == null ? null : truncate(request.getHeader("User-Agent"), 500))
                .build());
    }

    /**
     * Records a login attempt. Runs on its own thread and its own transaction, so a slow or
     * failing audit write never slows down or breaks logging in. The caller passes the IP and
     * browser because this thread cannot see the request.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAuthEvent(String action, String email, String ip, String userAgent) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .action(action)
                    .entityType("User")
                    .actorEmail(truncate(email, 255))
                    .ipAddress(truncate(ip, 45))
                    .userAgent(truncate(userAgent, 500))
                    .build());
        } catch (Exception e) {
            log.error("Audit log write failed for action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Log an action. Fire-and-forget: failures here don't affect the main operation.
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

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}