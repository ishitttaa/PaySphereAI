package com.paysphere.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N9: a merchant's dashboard shows ONLY that merchant's figures, and nothing platform-wide.
 *
 * Merchant A (hand-computed expectations):
 *   orders:   3  (PAYMENT_CAPTURED, PENDING, PAYMENT_INITIATED)  -> total 3, pending 2
 *   payments: CAPTURED 1000, FAILED 500                          -> gross 1000, success 1, failed 1
 *   refunds:  PROCESSED 300, PENDING 200                         -> refunded 300, refundsCount 2
 *   net revenue = 1000 - 300 = 700
 * Merchant B has much bigger numbers, so any leak would be obvious.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantDashboardIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private UUID merchantA;
    private UUID merchantB;

    @BeforeEach
    void setUp() {
        merchantA = createMerchant("Merchant A ");
        merchantB = createMerchant("Merchant B ");

        // Merchant A
        UUID a1 = insertOrder(merchantA, "PAYMENT_CAPTURED");
        UUID a2 = insertOrder(merchantA, "PENDING");
        insertOrder(merchantA, "PAYMENT_INITIATED");
        UUID aPaid = insertPayment(a1, "CAPTURED", 1000);
        insertPayment(a2, "FAILED", 500);
        insertRefund(aPaid, a1, "PROCESSED", 300);
        insertRefund(aPaid, a1, "PENDING", 200);

        // Merchant B: bigger numbers, must never show up in A's dashboard
        UUID b1 = insertOrder(merchantB, "PAYMENT_CAPTURED");
        insertOrder(merchantB, "PENDING");
        UUID bPaid = insertPayment(b1, "CAPTURED", 7777);
        insertPayment(b1, "FAILED", 4444);
        insertRefund(bPaid, b1, "PROCESSED", 777);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UUID createMerchant(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name) values (?, ?)", id, prefix + id);
        return id;
    }

    private UUID insertOrder(UUID merchantId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'ISO-PRODUCT', 'Isolation test', 1000, ?)",
                id, merchantId, "ORD-DASH-" + shortId(), status);
        return id;
    }

    private UUID insertPayment(UUID orderId, String status, long amountPaise) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into payments (id, business_order_id, razorpay_payment_id, razorpay_order_id, "
                + "status, amount_paise) values (?, ?, ?, ?, ?, ?)",
                id, orderId, "pay_dash_" + shortId(), "order_dash_" + shortId(), status, amountPaise);
        return id;
    }

    private void insertRefund(UUID paymentId, UUID orderId, String status, long amountPaise) {
        jdbc.update("insert into refunds (id, payment_id, business_order_id, amount_paise, status, "
                + "idempotency_key) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), paymentId, orderId, amountPaise, status, "dash-" + shortId());
    }

    private String merchantToken(UUID merchantId) {
        return jwtService.createToken(UUID.randomUUID(), "merchant@example.com",
                List.of("MERCHANT_ADMIN"), merchantId);
    }

    private JsonNode stats(String token) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/dashboard/stats")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aMerchantSeesExactlyItsOwnFigures() throws Exception {
        JsonNode data = stats(merchantToken(merchantA));

        assertThat(data.get("grossRevenuePaise").asLong()).isEqualTo(1000L);
        assertThat(data.get("refundedAmountPaise").asLong()).isEqualTo(300L);
        assertThat(data.get("totalRevenuePaise").asLong()).isEqualTo(700L);
        assertThat(data.get("successfulPaymentsCount").asLong()).isEqualTo(1L);
        assertThat(data.get("failedPaymentsCount").asLong()).isEqualTo(1L);
        assertThat(data.get("totalOrdersCount").asLong()).isEqualTo(3L);
        assertThat(data.get("pendingOrdersCount").asLong()).isEqualTo(2L);
        assertThat(data.get("refundsCount").asLong()).isEqualTo(2L);
    }

    @Test
    void aMerchantDoesNotSeePlatformWideFigures() throws Exception {
        JsonNode data = stats(merchantToken(merchantA));

        // Settlements, risk flags, webhooks and the ledger have no merchant link yet.
        // They are left out, not shown as a misleading 0.
        assertThat(data.has("settledAmountPaise")).isFalse();
        assertThat(data.has("openRiskFlagsCount")).isFalse();
        assertThat(data.has("webhookEventsCount")).isFalse();
        assertThat(data.has("ledgerTransactionsCount")).isFalse();
    }

    @Test
    void anotherMerchantsFiguresAreNotMixedIn() throws Exception {
        JsonNode data = stats(merchantToken(merchantB));

        assertThat(data.get("grossRevenuePaise").asLong()).isEqualTo(7777L);
        assertThat(data.get("refundedAmountPaise").asLong()).isEqualTo(777L);
        assertThat(data.get("totalOrdersCount").asLong()).isEqualTo(2L);
    }

    @Test
    void aMerchantUserWithoutAMerchantSeesZeros() throws Exception {
        String orphan = jwtService.createToken(UUID.randomUUID(), "orphan@example.com",
                List.of("MERCHANT_ADMIN"));
        JsonNode data = stats(orphan);

        assertThat(data.get("grossRevenuePaise").asLong()).isZero();
        assertThat(data.get("totalOrdersCount").asLong()).isZero();
        assertThat(data.get("refundsCount").asLong()).isZero();
    }

    @Test
    void platformStaffStillSeeThePlatformWideFigures() throws Exception {
        String staff = jwtService.createToken(UUID.randomUUID(), "staff@example.com",
                List.of("PLATFORM_ADMIN"));
        JsonNode data = stats(staff);

        assertThat(data.has("settledAmountPaise")).isTrue();
        assertThat(data.has("openRiskFlagsCount")).isTrue();
        assertThat(data.has("webhookEventsCount")).isTrue();
        assertThat(data.has("ledgerTransactionsCount")).isTrue();
        // Staff totals include both merchants.
        assertThat(data.get("grossRevenuePaise").asLong()).isGreaterThanOrEqualTo(1000L + 7777L);
    }

    @Test
    void aMerchantAdminStillCannotReachOtherDashboardRoutes() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/dashboard/summary")
                .header("Authorization", "Bearer " + merchantToken(merchantA))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }
}