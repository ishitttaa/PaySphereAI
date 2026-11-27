package com.paysphere.reconciliation;

import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.repository.RiskFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.paysphere.refund.service.RefundReconciler ;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    public static final Duration STALE_PENDING_REFUND_AFTER = Duration.ofMinutes(30);

    private final ReconciliationQueries queries;
    private final RiskFlagRepository riskFlagRepository;
    private final RefundReconciler refundReconciler;
    private final AuditService auditService;
    private final GatewayPaymentReconciler gatewayPaymentReconciler;

    /**
     * Runs every check. Each problem becomes a risk flag the first time it is seen.
     * A problem that already has a flag (open, reviewed or dismissed) is not flagged again.
     */
    @Transactional
    public ReconciliationReport run(Instant now, String actor) {
        // First settle refunds stuck in PENDING by asking the gateway, THEN check our own books,
        // so a refund that has just been settled is not reported as a problem.
         // 1. Settle refunds stuck in PENDING by asking the gateway.
        Map<String, Integer> refundsSettled = refundReconciler.reconcilePendingRefunds(now);
        // 2. Record payments the gateway captured that we never heard about, and compare the rest.
        GatewayPaymentResult gatewayPayments = gatewayPaymentReconciler.reconcile(now);
        // 3. Only now check our own books, so repairs from steps 1 and 2 are not reported as problems.
        List<ReconciliationFinding> findings =
                new ArrayList<>(queries.findAllProblems(now.minus(STALE_PENDING_REFUND_AFTER)));
        findings.addAll(gatewayPayments.findings());
        // ... flag loop waisa ka waisa ...
        int newFlags = 0;
        for (ReconciliationFinding f : findings) {
            if (riskFlagRepository.existsByEntityTypeAndEntityIdAndRuleCode(
                    f.entityType(), f.entityId(), f.ruleCode())) {
                continue;
            }
            riskFlagRepository.save(RiskFlag.builder()
                    .entityType(f.entityType())
                    .entityId(f.entityId())
                    .ruleCode(f.ruleCode())
                    .severity(f.severity())
                    .description(shorten(f.description()))
                    .build());
            newFlags++;
        }

        auditService.record("RECONCILIATION_RUN", "Reconciliation", null,
                findings.size() + " problem(s) found, " + newFlags + " new flag(s), pending refunds: "
                        + refundsSettled + ", gateway payments: " + gatewayPayments.outcomes(), actor);
        log.info("Reconciliation finished: {} problem(s), {} new flag(s)", findings.size(), newFlags);
        return new ReconciliationReport(now, findings.size(), newFlags, refundsSettled,
                gatewayPayments.outcomes(), findings);
    }

    private static String shorten(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}