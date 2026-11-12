package com.paysphere.risk;

import com.paysphere.payment.entity.Payment;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.entity.RiskSeverity;
import com.paysphere.risk.repository.RiskFlagRepository;
import com.paysphere.risk.service.RiskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock
    private RiskFlagRepository riskFlagRepository;

    @InjectMocks
    private RiskService riskService;

    @Test
    @DisplayName("evaluatePayment flags high amount payment over 50,000 INR")
    void testFlagHighAmount() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .amountPaise(6_000_000L) // Rs 60,000
                .international(false)
                .signatureVerified(true)
                .build();

        riskService.evaluatePayment(payment);

        ArgumentCaptor<RiskFlag> captor = ArgumentCaptor.forClass(RiskFlag.class);
        verify(riskFlagRepository).save(captor.capture());

        RiskFlag flag = captor.getValue();
        assertThat(flag.getRuleCode()).isEqualTo("AMOUNT_HIGH");
        assertThat(flag.getSeverity()).isEqualTo(RiskSeverity.HIGH);
    }

    @Test
    @DisplayName("evaluatePayment flags international card payment")
    void testFlagInternationalCard() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .amountPaise(100_000L) // Rs 1,000
                .international(true)
                .signatureVerified(true)
                .build();

        riskService.evaluatePayment(payment);

        ArgumentCaptor<RiskFlag> captor = ArgumentCaptor.forClass(RiskFlag.class);
        verify(riskFlagRepository).save(captor.capture());

        RiskFlag flag = captor.getValue();
        assertThat(flag.getRuleCode()).isEqualTo("INTERNATIONAL_CARD");
        assertThat(flag.getSeverity()).isEqualTo(RiskSeverity.MEDIUM);
    }

    @Test
    @DisplayName("evaluatePayment does not flag normal domestic low-value payment")
    void testNormalPaymentNoFlags() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .amountPaise(100_000L) // Rs 1,000
                .international(false)
                .signatureVerified(true)
                .build();

        riskService.evaluatePayment(payment);

        verify(riskFlagRepository, never()).save(any());
    }
}
