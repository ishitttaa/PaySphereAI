package com.paysphere.reconciliation;

import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.entity.RiskSeverity;
import com.paysphere.risk.repository.RiskFlagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private ReconciliationQueries queries;
    @Mock private RiskFlagRepository riskFlagRepository;
    @Mock private AuditService auditService;
    @InjectMocks private ReconciliationService service;

    private final Instant now = Instant.parse("2026-01-01T12:00:00Z");

    private ReconciliationFinding finding(UUID id) {
        return new ReconciliationFinding("RECON_PAYMENT_NOT_IN_LEDGER", "Payment", id,
                RiskSeverity.CRITICAL, "no ledger posting");
    }

    @Test
    void newProblemsBecomeRiskFlagsAndAreAudited() {
        UUID id = UUID.randomUUID();
        when(queries.findAllProblems(now.minus(ReconciliationService.STALE_PENDING_REFUND_AFTER)))
                .thenReturn(List.of(finding(id)));
        when(riskFlagRepository.existsByEntityTypeAndEntityIdAndRuleCode(
                "Payment", id, "RECON_PAYMENT_NOT_IN_LEDGER")).thenReturn(false);

        ReconciliationReport report = service.run(now, "TEST");

        assertThat(report.problemsFound()).isEqualTo(1);
        assertThat(report.newFlags()).isEqualTo(1);
        verify(riskFlagRepository).save(any(RiskFlag.class));
        verify(auditService).record(eq("RECONCILIATION_RUN"), eq("Reconciliation"), isNull(),
                contains("1 problem"), eq("TEST"));
    }

    @Test
    void aProblemThatAlreadyHasAFlagIsNotFlaggedAgain() {
        UUID id = UUID.randomUUID();
        when(queries.findAllProblems(now.minus(ReconciliationService.STALE_PENDING_REFUND_AFTER)))
                .thenReturn(List.of(finding(id)));
        when(riskFlagRepository.existsByEntityTypeAndEntityIdAndRuleCode(
                "Payment", id, "RECON_PAYMENT_NOT_IN_LEDGER")).thenReturn(true);

        ReconciliationReport report = service.run(now, "TEST");

        assertThat(report.problemsFound()).isEqualTo(1);
        assertThat(report.newFlags()).isZero();
        verify(riskFlagRepository, never()).save(any());
    }
}