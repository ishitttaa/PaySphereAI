package com.paysphere.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedGatewayTest {

    private final SimulatedGateway gateway = new SimulatedGateway("development");

    private Map<String, Object> paidOrder(long amount, boolean success) {
        String orderId = (String) gateway.createOrder(amount, "INR", "rcpt", "key").get("id");
        return gateway.payOrder(orderId, success, "card", false).orElseThrow();
    }

    @Test
    void successfulPaymentHasValidSignatureAndCorrectAmount() {
        Map<String, Object> pay = paidOrder(50000L, true);
        String orderId = (String) pay.get("razorpayOrderId");
        String paymentId = (String) pay.get("razorpayPaymentId");

        assertThat(gateway.verifyPaymentSignature(orderId, paymentId, (String) pay.get("razorpaySignature"))).isTrue();
        Map<String, Object> fetched = gateway.fetchPayment(paymentId);
        assertThat(fetched.get("amount")).isEqualTo(50000L);
        assertThat(fetched.get("captured")).isEqualTo(true);
    }

    @Test
    void tamperedSignatureIsRejected() {
        Map<String, Object> pay = paidOrder(50000L, true);
        assertThat(gateway.verifyPaymentSignature((String) pay.get("razorpayOrderId"),
                (String) pay.get("razorpayPaymentId"), "deadbeef")).isFalse();
    }

    @Test
    void failedPaymentHasNoSignatureAndIsNotCaptured() {
        Map<String, Object> pay = paidOrder(50000L, false);
        assertThat(pay.get("razorpaySignature")).isNull();
        assertThat(gateway.fetchPayment((String) pay.get("razorpayPaymentId")).get("captured")).isEqualTo(false);
    }

    @Test
    void sameRefundKeyReturnsSameRefund() {
        String paymentId = (String) paidOrder(50000L, true).get("razorpayPaymentId");
        Map<String, Object> first = gateway.createRefund(paymentId, 10000L, "r", "refund-key-0001");
        Map<String, Object> second = gateway.createRefund(paymentId, 10000L, "r", "refund-key-0001");
        assertThat(second.get("id")).isEqualTo(first.get("id"));
    }

    @Test
    void refundLargerThanPaymentIsRejectedLikeRazorpay() {
        String paymentId = (String) paidOrder(50000L, true).get("razorpayPaymentId");
            assertThatThrownBy(() -> gateway.createRefund(paymentId, 60000L, "r", "refund-key-0002"))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.isDefinitiveRejection()).isTrue());
    }

    @Test
    void refusesToRunInProduction() {
        assertThatThrownBy(() -> new SimulatedGateway("production"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void signedWebhookIsAcceptedAndTamperedOneIsNot() {
        String body = "{\"event\":\"refund.processed\"}";
        assertThat(gateway.verifyWebhookSignature(body, gateway.signWebhook(body))).isTrue();
        assertThat(gateway.verifyWebhookSignature(body + " ", gateway.signWebhook(body))).isFalse();
    }
}