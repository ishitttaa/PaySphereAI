package com.paysphere.merchant;

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
 * N9: merchant A must never read merchant B's refunds.
 * A refund belongs to a merchant through its business order.
 * Rows are inserted with SQL so the test does not depend on the checkout flow.
 * (Creating refunds stays PLATFORM_ADMIN only; merchant-initiated refunds are a later step.)
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantRefundIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private UUID merchantA;
    private UUID merchantB;
    private UUID orderA;
    private UUID orderB;
    private UUID paymentA;
    private UUID paymentB;
    private UUID refundA;
    private UUID refundB;

    @BeforeEach
    void setUp() {
        merchantA = createMerchant("Merchant A ");
        merchantB = createMerchant("Merchant B ");

        orderA = insertOrder(merchantA);
        orderB = insertOrder(merchantB);
        paymentA = insertPayment(orderA);
        paymentB = insertPayment(orderB);
        refundA = insertRefund(paymentA, orderA);
        refundB = insertRefund(paymentB, orderB);
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

    private UUID insertOrder(UUID merchantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'ISO-PRODUCT', 'Isolation test', 1000, "
                + "'PAYMENT_CAPTURED')", id, merchantId, "ORD-RISO-" + shortId());
        return id;
    }

    private UUID insertPayment(UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into payments (id, business_order_id, razorpay_payment_id, razorpay_order_id, "
                + "status, amount_paise) values (?, ?, ?, ?, 'CAPTURED', 1000)",
                id, orderId, "pay_riso_" + shortId(), "order_riso_" + shortId());
        return id;
    }

    private UUID insertRefund(UUID paymentId, UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into refunds (id, payment_id, business_order_id, amount_paise, status, "
                + "idempotency_key) values (?, ?, ?, 100, 'PENDING', ?)",
                id, paymentId, orderId, "riso-" + shortId());
        return id;
    }

    private String merchantToken(UUID merchantId) {
        return jwtService.createToken(UUID.randomUUID(), "merchant@example.com",
                List.of("MERCHANT_ADMIN"), merchantId);
    }

    private String platformToken() {
        return jwtService.createToken(UUID.randomUUID(), "staff@example.com", List.of("PLATFORM_ADMIN"));
    }

    private MvcResult get(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    private int listSize(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").size();
    }

    /** A list endpoint must answer 200 with nothing, or 404. A refund must never come back. */
    private void assertNothingReturned(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        assertThat(status).isIn(200, 404);
        if (status == 200) {
            assertThat(listSize(result)).isZero();
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aMerchantCanReadItsOwnRefundById() throws Exception {
        assertThat(get("/api/v1/refunds/" + refundA, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aMerchantCannotReadAnotherMerchantsRefundById() throws Exception {
        assertThat(get("/api/v1/refunds/" + refundB, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aMerchantSeesItsOwnRefundsForAPayment() throws Exception {
        MvcResult result = get("/api/v1/refunds/payment/" + paymentA, merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(listSize(result)).isEqualTo(1);
    }

    @Test
    void aMerchantSeesNoRefundsForAnotherMerchantsPayment() throws Exception {
        assertNothingReturned(get("/api/v1/refunds/payment/" + paymentB, merchantToken(merchantA)));
    }

    @Test
    void aMerchantSeesItsOwnRefundsForAnOrder() throws Exception {
        MvcResult result = get("/api/v1/refunds/order/" + orderA, merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(listSize(result)).isEqualTo(1);
    }

    @Test
    void aMerchantSeesNoRefundsForAnotherMerchantsOrder() throws Exception {
        assertNothingReturned(get("/api/v1/refunds/order/" + orderB, merchantToken(merchantA)));
    }

    @Test
    void aMerchantUserWithoutAMerchantSeesNoRefunds() throws Exception {
        String orphan = jwtService.createToken(UUID.randomUUID(), "orphan@example.com",
                List.of("MERCHANT_ADMIN"));

        assertThat(get("/api/v1/refunds/" + refundA, orphan).getResponse().getStatus()).isEqualTo(404);
        assertNothingReturned(get("/api/v1/refunds/payment/" + paymentA, orphan));
        assertNothingReturned(get("/api/v1/refunds/order/" + orderA, orphan));
    }

    @Test
    void platformStaffCanReadRefundsOfEveryMerchant() throws Exception {
        String staff = platformToken();

        assertThat(get("/api/v1/refunds/" + refundA, staff).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/v1/refunds/" + refundB, staff).getResponse().getStatus()).isEqualTo(200);
    }
}