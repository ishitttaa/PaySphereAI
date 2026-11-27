package com.paysphere.risk.service;

import com.paysphere.payment.entity.Payment;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.entity.RiskFlagStatus;
import com.paysphere.risk.entity.RiskSeverity;
import com.paysphere.risk.repository.RiskFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.paysphere.audit.service.AuditService;
import com.paysphere.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RiskService — Evaluates deterministic risk rules on payments.
 *
 * Rules implemented:
 * - AMOUNT_HIGH: payment > 50,000 INR (5,000,000 paise) — HIGH severity
 * - INTERNATIONAL_CARD: card issued outside India — MEDIUM severity
 *
 * These are simple rule-based checks. In production you'd add:
 * - Velocity checks (N payments from same email in M minutes)
 * - IP reputation checks
 * - Card BIN list checks
 *
 * Flags are reviewed manually by admin. They do NOT block payments.
 * A payment can be CAPTURED and still have open risk flags.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskFlagRepository riskFlagRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    // Threshold: flag payments above Rs 50,000 (5,000,000 paise)
    private static final long HIGH_AMOUNT_THRESHOLD_PAISE = 5_000_000L;

    /**
     * Evaluate risk rules for a captured payment.
     * Called after payment.captured event (via webhook or verify flow).
     */
    @Transactional
    public void evaluatePayment(Payment payment) {
        if (payment == null) return;

        // Rule 1: High-value payment threshold
        if (payment.getAmountPaise() != null
                && payment.getAmountPaise() >= HIGH_AMOUNT_THRESHOLD_PAISE) {
            raiseFlag(payment.getId(), "Payment",
                    "AMOUNT_HIGH", RiskSeverity.HIGH,
                    String.format("Payment amount %d paise (Rs %.2f) exceeds Rs 50,000 threshold",
                            payment.getAmountPaise(), payment.getAmountPaise() / 100.0));
        }

        // Rule 2: International card
        if (Boolean.TRUE.equals(payment.getInternational())) {
            raiseFlag(payment.getId(), "Payment",
                    "INTERNATIONAL_CARD", RiskSeverity.MEDIUM,
                    "Payment made with an international card. Verify customer identity.");
        }

        // Rule 3: Unverified signature (shouldn't happen in normal flow, but flag if it does)
        if (Boolean.FALSE.equals(payment.getSignatureVerified())) {
            raiseFlag(payment.getId(), "Payment",
                    "UNVERIFIED_SIGNATURE", RiskSeverity.CRITICAL,
                    "Payment captured without verified Razorpay signature. Investigate immediately.");
        }
    }

    /** Get all open risk flags, paginated. */
    @Transactional(readOnly = true)
    public Page<RiskFlag> getOpenFlags(Pageable pageable) {
        return riskFlagRepository.findByStatusOrderByCreatedAtDesc(RiskFlagStatus.OPEN, pageable);
    }

    /** Get all flags for a specific payment/order. */
    @Transactional(readOnly = true)
    public List<RiskFlag> getFlagsForEntity(String entityType, UUID entityId) {
        return riskFlagRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    /** Dismiss a risk flag after review. */
        /** Dismiss a risk flag after review. The reviewer comes from the login token, never the request body. */
    @Transactional
    public void dismissFlag(UUID flagId, UUID reviewerUserId, String notes) {
        RiskFlag flag = riskFlagRepository.findById(flagId)
                .orElseThrow(() -> new IllegalArgumentException("Risk flag not found: " + flagId));
        flag.setStatus(RiskFlagStatus.DISMISSED);
        // reviewed_by is a foreign key to users: only store an id that really exists.
        flag.setReviewedBy(reviewerUserId != null && userRepository.existsById(reviewerUserId)
                ? reviewerUserId : null);
        flag.setReviewedAt(Instant.now());
        flag.setReviewNotes(notes == null ? null : (notes.length() > 500 ? notes.substring(0, 500) : notes));
        riskFlagRepository.save(flag);
        auditService.record("RISK_FLAG_DISMISSED", "RiskFlag", flag.getId(),
                "Rule " + flag.getRuleCode() + " on " + flag.getEntityType() + " " + flag.getEntityId(), "API");
    }

    private void raiseFlag(UUID entityId, String entityType,
                            String ruleCode, RiskSeverity severity, String description) {
        RiskFlag flag = RiskFlag.builder()
                .entityType(entityType)
                .entityId(entityId)
                .ruleCode(ruleCode)
                .severity(severity)
                .description(description)
                .status(RiskFlagStatus.OPEN)
                .build();
        riskFlagRepository.save(flag);
        log.warn("Risk flag raised: entity={}/{}, rule={}, severity={}",
                entityType, entityId, ruleCode, severity);
    }
}
