package com.paysphere.audit.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditLog — immutable record of every significant action in the system.
 *
 * WHY audit logs?
 * - Compliance: know who did what, when, and why
 * - Security: detect suspicious admin actions
 * - Debugging: replay events to understand failures
 * - Accountability: track payment-related decisions
 *
 * Audit logs are APPEND-ONLY. Never updated or deleted.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;  // JSON string

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
