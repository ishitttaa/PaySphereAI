package com.paysphere.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * RiskFlag — raised when a deterministic risk rule triggers on a payment.
 *
 * Risk rules are deterministic (rule-based), not ML-based. Examples:
 * - AMOUNT_THRESHOLD: single payment > Rs 50,000
 * - HIGH_VELOCITY: >5 payments from same email in 1 hour
 * - INTERNATIONAL_CARD: card issued outside India
 * - SUSPICIOUS_PATTERN: multiple card declines then success
 *
 * Flags are reviewed by a human admin and either DISMISSED or REVIEWED.
 */
@Entity
@Table(name = "risk_flags")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "rule_code", nullable = false, length = 50)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private RiskSeverity severity;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RiskFlagStatus status = RiskFlagStatus.OPEN;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes", length = 500)
    private String reviewNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
