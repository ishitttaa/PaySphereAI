package com.paysphere.risk;

import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.entity.RiskFlagStatus;
import com.paysphere.risk.entity.RiskSeverity;
import com.paysphere.risk.repository.RiskFlagRepository;
import com.paysphere.risk.service.RiskService;
import com.paysphere.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskDismissTest {

    @Mock private RiskFlagRepository riskFlagRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private RiskService riskService;

    private RiskFlag flag(UUID id) {
        return RiskFlag.builder().id(id).entityType("Payment").entityId(UUID.randomUUID())
                .ruleCode("INTERNATIONAL_CARD").severity(RiskSeverity.MEDIUM).description("d").build();
    }

    @Test
    void aReviewerWhoDoesNotExistIsNotStoredAndNothingFailsBecauseOfTheForeignKey() {
        UUID flagId = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        RiskFlag flag = flag(flagId);
        when(riskFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(userRepository.existsById(ghost)).thenReturn(false);

        riskService.dismissFlag(flagId, ghost, "x".repeat(900));

        assertThat(flag.getStatus()).isEqualTo(RiskFlagStatus.DISMISSED);
        assertThat(flag.getReviewedBy()).isNull();
        assertThat(flag.getReviewNotes()).hasSize(500);
        verify(auditService).record(eq("RISK_FLAG_DISMISSED"), eq("RiskFlag"), eq(flagId), anyString(), eq("API"));
    }

    @Test
    void aRealReviewerIsStored() {
        UUID flagId = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        RiskFlag flag = flag(flagId);
        when(riskFlagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(userRepository.existsById(reviewer)).thenReturn(true);

        riskService.dismissFlag(flagId, reviewer, "checked");

        assertThat(flag.getReviewedBy()).isEqualTo(reviewer);
        verify(riskFlagRepository).save(any(RiskFlag.class));
    }
}