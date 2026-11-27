package com.paysphere.settlement;

import com.paysphere.audit.service.AuditService;
import com.paysphere.ledger.service.LedgerService;
import com.paysphere.settlement.entity.Settlement;
import com.paysphere.settlement.repository.SettlementRepository;
import com.paysphere.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private LedgerService ledgerService;
    @Mock private AuditService auditService;
    @InjectMocks private SettlementService service;

    @Test
    void recordingASettlementPostsTheLedgerAndWritesAnAuditEntry() {
        when(settlementRepository.existsByRazorpaySettlementId("setl_1")).thenReturn(false);
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(i -> i.getArgument(0));

        service.recordSettlement("setl_1", 100000L, 2000L, 0L, "UTR123");

        verify(ledgerService).postSettlement(any(), eq(100000L), eq(2000L), eq("INR"));
        verify(auditService).record(eq("SETTLEMENT_RECORDED"), eq("Settlement"), any(),
                contains("setl_1"), eq("API"));
    }

    @Test
    void aSettlementThatWasAlreadyRecordedChangesNothing() {
        when(settlementRepository.existsByRazorpaySettlementId("setl_2")).thenReturn(true);
        when(settlementRepository.findByRazorpaySettlementId("setl_2"))
                .thenReturn(Optional.of(Settlement.builder().razorpaySettlementId("setl_2").build()));

        service.recordSettlement("setl_2", 100000L, 2000L, 0L, "UTR456");

        verify(ledgerService, never()).postSettlement(any(), anyLong(), anyLong(), anyString());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}