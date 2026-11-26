package com.paysphere.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.junit.jupiter.api.AfterEach;
import com.paysphere.gateway.SimulatedGateway;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds real, consistent data through the public API (with the payment simulator), then damages
 * it directly in the database and checks that reconciliation notices.
 * Each test looks only at the rows it created, because the database is shared with other tests.
 */

@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class ReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private SimulatedGateway simulatedGateway;

    private record Paid(UUID orderId, UUID paymentId) {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode post(String path, String json, String... headers) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(path + " -> " + result.getResponse().getContentAsString()).isLessThan(300);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Paid createCapturedPayment() throws Exception {
        JsonNode order = post("/api/v1/orders",
                "{\"productCode\":\"PAYSPHERE-BASIC\",\"customerName\":\"Recon Test\","
                        + "\"customerEmail\":\"recon@example.com\",\"customerPhone\":\"9999999999\"}");
        String razorpayOrderId = order.get("razorpayOrderId").asText();
        JsonNode pay = post("/api/v1/simulator/pay",
                "{\"razorpayOrderId\":\"" + razorpayOrderId + "\",\"outcome\":\"SUCCESS\"}");
        JsonNode payment = post("/api/v1/payments/verify",
                "{\"razorpayPaymentId\":\"" + pay.get("razorpayPaymentId").asText()
                        + "\",\"razorpayOrderId\":\"" + razorpayOrderId
                        + "\",\"razorpaySignature\":\"" + pay.get("razorpaySignature").asText() + "\"}");
        UUID paymentId = UUID.fromString(payment.get("id").asText());
        UUID orderId = jdbc.queryForObject(
                "select business_order_id from payments where id = ?", UUID.class, paymentId);
        return new Paid(orderId, paymentId);
    }

    
    private JsonNode requestRefund(Paid paid, long amountPaise) throws Exception {
        String adminToken = jwtService.createToken(
                UUID.randomUUID(), "recon@example.com", List.of("PLATFORM_ADMIN"));
        return post("/api/v1/refunds",
                "{\"paymentId\":\"" + paid.paymentId() + "\",\"amountPaise\":" + amountPaise
                        + ",\"reason\":\"recon\"}",
                "Authorization", "Bearer " + adminToken,
                "Idempotency-Key", "recon-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private UUID createRefund(Paid paid, long amountPaise) throws Exception {
        JsonNode refund = requestRefund(paid, amountPaise);
        assertThat(refund.get("status").asText()).isEqualTo("PROCESSED");
        return UUID.fromString(refund.get("id").asText());
    }

    private void ageRefund(UUID refundId) {
        jdbc.update("update refunds set created_at = now() - interval '2 hours' where id = ?", refundId);
    }

    @AfterEach
    void cleanUp() {
        simulatedGateway.setRefundMode(SimulatedGateway.RefundMode.NORMAL);
        // Leave no stale PENDING refunds behind for other tests sharing this database.
        jdbc.update("update refunds set status = 'FAILED' where status = 'PENDING'");
    }
    private ReconciliationReport reconcile() {
        return reconciliationService.run(Instant.now(), "TEST");
    }

    private boolean flagged(ReconciliationReport report, String ruleCode, UUID entityId) {
        return report.findings().stream()
                .anyMatch(f -> f.ruleCode().equals(ruleCode) && f.entityId().equals(entityId));
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void consistentDataHasNoFindings() throws Exception {
        Paid paid = createCapturedPayment();
        UUID refundId = createRefund(paid, 20000L);

        ReconciliationReport report = reconcile();

        assertThat(report.findings())
                .noneMatch(f -> f.entityId().equals(paid.paymentId()) || f.entityId().equals(refundId));
    }

    @Test
    void detectsACapturedPaymentThatWasNeverPostedToTheLedger() throws Exception {
        Paid base = createCapturedPayment();
        String razorpayPaymentId = "pay_manual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("insert into payments (business_order_id, razorpay_payment_id, razorpay_order_id, "
                        + "status, amount_paise) values (?, ?, 'order_manual_x', 'CAPTURED', 1000)",
                base.orderId(), razorpayPaymentId);
        UUID orphan = jdbc.queryForObject(
                "select id from payments where razorpay_payment_id = ?", UUID.class, razorpayPaymentId);

        assertThat(flagged(reconcile(), "RECON_PAYMENT_NOT_IN_LEDGER", orphan)).isTrue();
    }

    @Test
    void detectsALedgerAmountThatDiffersFromThePayment() throws Exception {
        Paid paid = createCapturedPayment();
        jdbc.update("update payments set amount_paise = amount_paise + 100 where id = ?", paid.paymentId());

        assertThat(flagged(reconcile(), "RECON_PAYMENT_LEDGER_AMOUNT_MISMATCH", paid.paymentId())).isTrue();
    }

    @Test
    void detectsARefundedTotalThatDoesNotMatchTheRefunds() throws Exception {
        Paid paid = createCapturedPayment();
        jdbc.update("update payments set amount_refunded_paise = 500 where id = ?", paid.paymentId());

        assertThat(flagged(reconcile(), "RECON_REFUND_TOTAL_MISMATCH", paid.paymentId())).isTrue();
    }

    @Test
    void detectsAProcessedRefundThatWasNeverPostedToTheLedger() throws Exception {
        Paid paid = createCapturedPayment();
        UUID refundId = UUID.randomUUID();
        jdbc.update("insert into refunds (id, payment_id, business_order_id, amount_paise, status, "
                        + "idempotency_key) values (?, ?, ?, 700, 'PROCESSED', ?)",
                refundId, paid.paymentId(), paid.orderId(), "manual-" + refundId);

        assertThat(flagged(reconcile(), "RECON_REFUND_NOT_IN_LEDGER", refundId)).isTrue();
    }

    @Test
    void detectsAFailedRefundWhoseLedgerEntryWasNeverReversed() throws Exception {
        Paid paid = createCapturedPayment();
        UUID refundId = createRefund(paid, 20000L);
        jdbc.update("update refunds set status = 'FAILED' where id = ?", refundId);

        assertThat(flagged(reconcile(), "RECON_FAILED_REFUND_NOT_REVERSED", refundId)).isTrue();
    }

        @Test
    void detectsARefundThatStaysPendingBecauseTheGatewayCannotBeAsked() throws Exception {
        Paid base = createCapturedPayment();
        String razorpayPaymentId = "pay_unknown_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("insert into payments (business_order_id, razorpay_payment_id, razorpay_order_id, "
                        + "status, amount_paise) values (?, ?, 'order_manual_y', 'CAPTURED', 1000)",
                base.orderId(), razorpayPaymentId);
        UUID paymentId = jdbc.queryForObject(
                "select id from payments where razorpay_payment_id = ?", UUID.class, razorpayPaymentId);
        UUID refundId = UUID.randomUUID();
        jdbc.update("insert into refunds (id, payment_id, business_order_id, amount_paise, status, "
                        + "idempotency_key, created_at) values (?, ?, ?, 300, 'PENDING', ?, now() - interval '2 hours')",
                refundId, paymentId, base.orderId(), "manual-" + refundId);

        assertThat(flagged(reconcile(), "RECON_STALE_PENDING_REFUND", refundId)).isTrue();
    }

    @Test
    void runningTwiceDoesNotCreateDuplicateFlags() throws Exception {
        Paid paid = createCapturedPayment();
        jdbc.update("update payments set amount_refunded_paise = 500 where id = ?", paid.paymentId());

        reconcile();
        reconcile();

        Integer flags = jdbc.queryForObject(
                "select count(*) from risk_flags where entity_id = ? and rule_code = 'RECON_REFUND_TOTAL_MISMATCH'",
                Integer.class, paid.paymentId());
        assertThat(flags).isEqualTo(1);
    }
}