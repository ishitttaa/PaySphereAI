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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N9: merchant A must never list or read merchant B's payments.
 * A payment belongs to a merchant through its business order.
 * Rows are inserted with SQL so the test does not depend on the checkout flow.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantPaymentIsolationIntegrationTest extends BaseIntegrationTest {

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
    private String razorpayPaymentIdA;
    private String razorpayPaymentIdB;

    @BeforeEach
    void setUp() {
        merchantA = createMerchant("Merchant A ");
        merchantB = createMerchant("Merchant B ");

        orderA = insertOrder(merchantA);
        orderB = insertOrder(merchantB);

        razorpayPaymentIdA = "pay_iso_" + shortId();
        razorpayPaymentIdB = "pay_iso_" + shortId();
        paymentA = insertPayment(orderA, razorpayPaymentIdA);
        paymentB = insertPayment(orderB, razorpayPaymentIdB);
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
                + "'PAYMENT_CAPTURED')", id, merchantId, "ORD-PISO-" + shortId());
        return id;
    }

    private UUID insertPayment(UUID orderId, String razorpayPaymentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into payments (id, business_order_id, razorpay_payment_id, razorpay_order_id, "
                + "status, amount_paise) values (?, ?, ?, ?, 'CAPTURED', 1000)",
                id, orderId, razorpayPaymentId, "order_iso_" + shortId());
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

    private Set<String> razorpayIdsInPage(MvcResult result) throws Exception {
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("content");
        Set<String> ids = new HashSet<>();
        content.forEach(node -> ids.add(node.get("razorpayPaymentId").asText()));
        return ids;
    }

    private int paymentCountForOrder(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").size();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aMerchantListsOnlyItsOwnPayments() throws Exception {
        MvcResult result = get("/api/v1/payments?size=100", merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Set<String> ids = razorpayIdsInPage(result);
        assertThat(ids).contains(razorpayPaymentIdA);
        assertThat(ids).doesNotContain(razorpayPaymentIdB);
    }

    @Test
    void aMerchantCanReadItsOwnPaymentById() throws Exception {
        assertThat(get("/api/v1/payments/" + paymentA, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aMerchantCannotReadAnotherMerchantsPaymentById() throws Exception {
        assertThat(get("/api/v1/payments/" + paymentB, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aMerchantSeesItsOwnPaymentsForAnOrder() throws Exception {
        MvcResult result = get("/api/v1/payments/order/" + orderA, merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(paymentCountForOrder(result)).isEqualTo(1);
    }

    @Test
    void aMerchantSeesNoPaymentsForAnotherMerchantsOrder() throws Exception {
        MvcResult result = get("/api/v1/payments/order/" + orderB, merchantToken(merchantA));

        // Either 404 or an empty list is safe; what must never happen is a payment coming back.
        int status = result.getResponse().getStatus();
        assertThat(status).isIn(200, 404);
        if (status == 200) {
            assertThat(paymentCountForOrder(result)).isZero();
        }
    }

    @Test
    void aMerchantUserWithoutAMerchantSeesNoPayments() throws Exception {
        String orphan = jwtService.createToken(UUID.randomUUID(), "orphan@example.com",
                List.of("MERCHANT_ADMIN"));

        MvcResult list = get("/api/v1/payments?size=100", orphan);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(razorpayIdsInPage(list)).isEmpty();
        assertThat(get("/api/v1/payments/" + paymentA, orphan).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void platformStaffCanReadPaymentsOfEveryMerchant() throws Exception {
        String staff = platformToken();

        assertThat(get("/api/v1/payments/" + paymentA, staff).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/v1/payments/" + paymentB, staff).getResponse().getStatus()).isEqualTo(200);
    }
}