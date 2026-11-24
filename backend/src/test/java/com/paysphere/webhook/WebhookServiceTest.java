package com.paysphere.webhook;

import com.paysphere.refund.service.RefundService;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.payment.service.PaymentService;
import com.paysphere.webhook.entity.WebhookEvent;
import com.paysphere.webhook.entity.WebhookStatus;
import com.paysphere.webhook.repository.WebhookEventRepository;
import com.paysphere.webhook.service.WebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;


@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {
    
    @Mock 
    private RefundService refundService;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private WebhookService webhookService;

    @Test
    @DisplayName("processWebhook rejects unverified signature immediately")
    void testInvalidSignatureThrowsSecurityException() {
        String payload = "{\"id\":\"evt_123\",\"event\":\"payment.captured\"}";
        String signature = "invalid_sig";

        when(paymentGateway.verifyWebhookSignature(payload, signature)).thenReturn(false);

        assertThatThrownBy(() -> webhookService.processWebhook(payload, signature))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("signature verification failed");

        verify(webhookEventRepository, never()).save(any());
        verify(paymentService, never()).handleWebhookCapture(any(), any(), any());
    }

    @Test
    @DisplayName("processWebhook skips processing if event ID is duplicate (idempotent)")
    void testDuplicateEventSkipped() {
        String payload = "{\"id\":\"evt_duplicate\",\"event\":\"payment.captured\"}";
        String signature = "valid_sig";

        when(paymentGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_duplicate")).thenReturn(true);

        webhookService.processWebhook(payload, signature);

        verify(webhookEventRepository, never()).save(argThat(evt -> evt.getRazorpayEventId().equals("evt_duplicate") && evt.getStatus() == WebhookStatus.PENDING));
        verify(paymentService, never()).handleWebhookCapture(any(), any(), any());
    }

    @Test
    @DisplayName("processWebhook processes valid event and transitions status to PROCESSED")
    void testValidEventProcessed() {
        String payload = "{\"id\":\"evt_test_new\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_123\",\"order_id\":\"order_123\",\"amount\":10000,\"currency\":\"INR\",\"status\":\"captured\"}}}}";
        String signature = "valid_sig";

        when(paymentGateway.verifyWebhookSignature(payload, signature)).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_test_new")).thenReturn(false);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> {
            WebhookEvent evt = i.getArgument(0);
            if (evt.getId() == null) evt.setId(UUID.randomUUID());
            return evt;
        });

        webhookService.processWebhook(payload, signature);

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(captor.capture());

        WebhookEvent lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSaved.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(lastSaved.getRazorpayEventId()).isEqualTo("evt_test_new");
    }
        @Test
    @DisplayName("processWebhook records FAILED and throws so Razorpay retries")
    void testFailureIsRecordedAndRethrown() {
        String payload = "{\"id\":\"evt_fail\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":10000}}}}";
        when(paymentGateway.verifyWebhookSignature(payload, "sig")).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_fail")).thenReturn(false);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new RuntimeException("ledger down"))
                .when(paymentService).handleWebhookCapture(any(), any(), any());

        assertThatThrownBy(() -> webhookService.processWebhook(payload, "sig"))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(captor.capture());
        WebhookEvent last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(last.getErrorMessage()).contains("ledger down");
    }

    @Test
    @DisplayName("processWebhook retries an event that previously FAILED")
    void testFailedEventIsRetried() {
        String payload = "{\"id\":\"evt_retry\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_2\",\"order_id\":\"order_2\",\"amount\":10000}}}}";
        WebhookEvent failed = WebhookEvent.builder()
                .razorpayEventId("evt_retry").status(WebhookStatus.FAILED).build();
        when(paymentGateway.verifyWebhookSignature(payload, "sig")).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_retry")).thenReturn(true);
        when(webhookEventRepository.findByRazorpayEventId("evt_retry")).thenReturn(java.util.Optional.of(failed));
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        webhookService.processWebhook(payload, "sig");

        verify(paymentService).handleWebhookCapture(any(), any(), any());
        assertThat(failed.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
    }
        private String refundProcessedPayload(String eventId, String refundId, String paymentId, long amount) {
        return "{\"id\":\"" + eventId + "\",\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\""
                + refundId + "\",\"payment_id\":\"" + paymentId + "\",\"amount\":" + amount + ",\"currency\":\"INR\"}}}}";
    }

    @Test
    @DisplayName("refund.processed webhook passes refund id, payment id and amount to RefundService")
    void testRefundProcessedIsApplied() {
        String payload = refundProcessedPayload("evt_r1", "rfnd_1", "pay_1", 20000L);
        when(paymentGateway.verifyWebhookSignature(payload, "sig")).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_r1")).thenReturn(false);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        webhookService.processWebhook(payload, "sig");

        verify(refundService).handleWebhookRefundProcessed("rfnd_1", "pay_1", 20000L);
    }

    @Test
    @DisplayName("refund.processed without a refund entity is ignored, not retried forever")
    void testRefundProcessedWithoutEntityIsIgnored() {
        String payload = "{\"id\":\"evt_r2\",\"event\":\"refund.processed\",\"payload\":{}}";
        when(paymentGateway.verifyWebhookSignature(payload, "sig")).thenReturn(true);
        when(webhookEventRepository.existsByRazorpayEventId("evt_r2")).thenReturn(false);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        webhookService.processWebhook(payload, "sig");

        verify(refundService, never()).handleWebhookRefundProcessed(any(), any(), anyLong());
    }
}
