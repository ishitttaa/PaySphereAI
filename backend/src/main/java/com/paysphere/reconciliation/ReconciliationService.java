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

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    public static final Duration STALE_PENDING_REFUND_AFTER = Duration.ofMinutes(30);

    private final ReconciliationQueries queries;
    private final RiskFlagRepository riskFlagRepository;
    private final RefundReconciler refundReconciler;
    private final AuditService auditService;

    /**
     * Runs every check. Each problem becomes a risk flag the first time it is seen.
     * A problem that already has a flag (open, reviewed or dismissed) is not flagged again.
     */
    @Transactional
    public ReconciliationReport run(Instant now, String actor) {
        // First settle refunds stuck in PENDING by asking the gateway, THEN check our own books,
        // so a refund that has just been settled is not reported as a problem.
        Map<String, Integer> refundsSettled = refundReconciler.reconcilePendingRefunds(now);

        List<ReconciliationFinding> findings =
                queries.findAllProblems(now.minus(STALE_PENDING_REFUND_AFTER));
        // ... (loop jo flags banata hai, waisa ka waisa)

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
                findings.size() + " problem(s) found, " + newFlags + " new flag(s), pending refunds settled: "
                        + refundsSettled, actor);
        log.info("Reconciliation finished: {} problem(s), {} new flag(s)", findings.size(), newFlags);
        return new ReconciliationReport(now, findings.size(), newFlags, refundsSettled, findings);
    }

    private static String shorten(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}