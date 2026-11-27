package com.paysphere.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.reconciliation.ReconciliationReport;
import com.paysphere.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N3: the payment.failed webhook, end to end with the payment simulator.
 * Each test uses its own order and payment ids, because the database is shared with other tests.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class PaymentFailedWebhookIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReconciliationService reconciliationService;

    private record Order(String razorpayOrderId, long amountPaise) {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode post(String path, String json) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(path + " -> " + result.getResponse().getContentAsString()).isLessThan(300);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Order createOrder() throws Exception {
        JsonNode order = post("/api/v1/orders",
                "{\"productCode\":\"PAYSPHERE-BASIC\",\"customerName\":\"Failed Attempt\","
                        + "\"customerEmail\":\"failed@example.com\",\"customerPhone\":\"9999999999\"}");
        String razorpayOrderId = order.get("razorpayOrderId").asText();
        long amount = jdbc.queryForObject(
                "select amount_paise from business_orders where razorpay_order_id = ?",
                Long.class, razorpayOrderId);
        return new Order(razorpayOrderId, amount);
    }

    private String newPaymentId() {
        return "pay_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private void webhook(String eventType, String paymentId, Order order, String eventId) throws Exception {
        post("/api/v1/simulator/webhook",
                "{\"eventType\":\"" + eventType + "\",\"paymentId\":\"" + paymentId
                        + "\",\"razorpayOrderId\":\"" + order.razorpayOrderId()
                        + "\",\"amountPaise\":" + order.amountPaise()
                        + (eventId == null ? "" : ",\"eventId\":\"" + eventId + "\"") + "}");
    }

    private int paymentRows(String paymentId) {
        return jdbc.queryForObject("select count(*) from payments where razorpay_payment_id = ?",
                Integer.class, paymentId);
    }

    private String paymentStatus(String paymentId) {
        return jdbc.queryForObject("select status from payments where razorpay_payment_id = ?",
                String.class, paymentId);
    }

    private String paymentColumn(String column, String paymentId) {
        return jdbc.queryForObject("select " + column + " from payments where razorpay_payment_id = ?",
                String.class, paymentId);
    }

    private String orderStatus(Order order) {
        return jdbc.queryForObject("select status from business_orders where razorpay_order_id = ?",
                String.class, order.razorpayOrderId());
    }

    private int ledgerPostings(String paymentId) {
        return jdbc.queryForObject(
                "select count(*) from ledger_transactions t join payments p "
                        + "on t.idempotency_key = 'PAYMENT-' || p.id::text where p.razorpay_payment_id = ?",
                Integer.class, paymentId);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aFailedPaymentWebhookRecordsTheAttemptAndFailsTheOrder() throws Exception {
        Order order = createOrder();
        String paymentId = newPaymentId();
        String eventId = "evt_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        webhook("payment.failed", paymentId, order, eventId);
        webhook("payment.failed", paymentId, order, eventId);   // duplicate delivery of the same event

        assertThat(paymentRows(paymentId)).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(paymentColumn("error_code", paymentId)).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(paymentColumn("error_reason", paymentId)).isEqualTo("payment_failed");
        assertThat(orderStatus(order)).isEqualTo("PAYMENT_FAILED");
        assertThat(ledgerPostings(paymentId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs a join payments p on a.entity_id = p.id "
                        + "where a.action = 'PAYMENT_FAILED' and p.razorpay_payment_id = ?",
                Integer.class, paymentId)).isEqualTo(1);
    }

    @Test
    void aLateCaptureUpgradesTheFailedAttemptInPlace() throws Exception {
        Order order = createOrder();
        String paymentId = newPaymentId();

        webhook("payment.failed", paymentId, order, null);
        webhook("payment.captured", paymentId, order, null);

        assertThat(paymentRows(paymentId)).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("CAPTURED");
        assertThat(paymentColumn("error_code", paymentId)).isNull();
        assertThat(ledgerPostings(paymentId)).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo("PAYMENT_CAPTURED");
    }

    @Test
    void aLateFailureNeverDowngradesACapturedPayment() throws Exception {
        Order order = createOrder();
        String paymentId = newPaymentId();

        webhook("payment.captured", paymentId, order, null);
        webhook("payment.failed", paymentId, order, null);

        assertThat(paymentRows(paymentId)).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("CAPTURED");
        assertThat(orderStatus(order)).isEqualTo("PAYMENT_CAPTURED");
        assertThat(ledgerPostings(paymentId)).isEqualTo(1);
    }

    @Test
    void reconciliationStillFindsAPaidRetryAfterAFailedAttempt() throws Exception {
        Order order = createOrder();
        String failedId = newPaymentId();
        webhook("payment.failed", failedId, order, null);
        // The customer retried and paid at the gateway, but verify and the capture webhook were both lost.
        JsonNode retry = post("/api/v1/simulator/pay",
                "{\"razorpayOrderId\":\"" + order.razorpayOrderId()
                        + "\",\"outcome\":\"SUCCESS\",\"minutesAgo\":30}");
        String paidId = retry.get("razorpayPaymentId").asText();

        ReconciliationReport report = reconciliationService.run(Instant.now(), "TEST");

        assertThat(report.gatewayPayments()).containsKey("RECORDED");
        assertThat(paymentStatus(paidId)).isEqualTo("CAPTURED");
        assertThat(paymentStatus(failedId)).isEqualTo("FAILED");
        assertThat(orderStatus(order)).isEqualTo("PAYMENT_CAPTURED");
    }
}