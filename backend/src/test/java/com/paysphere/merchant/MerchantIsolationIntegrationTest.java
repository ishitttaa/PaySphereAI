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
 * N9: merchant A must never read, list or filter merchant B's orders.
 * Orders are inserted with SQL so the test does not depend on the checkout flow.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private UUID merchantA;
    private UUID merchantB;
    private String aPendingRef;
    private String aCapturedRef;
    private String bCapturedRef;
    private UUID aCapturedId;
    private UUID bCapturedId;

    @BeforeEach
    void setUp() {
        merchantA = createMerchant("Merchant A ");
        merchantB = createMerchant("Merchant B ");

        aPendingRef = "ORD-ISO-" + shortId();
        aCapturedRef = "ORD-ISO-" + shortId();
        bCapturedRef = "ORD-ISO-" + shortId();

        insertOrder(merchantA, aPendingRef, "PENDING");
        aCapturedId = insertOrder(merchantA, aCapturedRef, "PAYMENT_CAPTURED");
        bCapturedId = insertOrder(merchantB, bCapturedRef, "PAYMENT_CAPTURED");
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

    private UUID insertOrder(UUID merchantId, String reference, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'ISO-PRODUCT', 'Isolation test', 1000, ?)",
                id, merchantId, reference, status);
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

    private Set<String> referencesIn(MvcResult result) throws Exception {
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("content");
        Set<String> refs = new HashSet<>();
        content.forEach(node -> refs.add(node.get("orderReference").asText()));
        return refs;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aMerchantListsOnlyItsOwnOrders() throws Exception {
        MvcResult result = get("/api/v1/orders?size=100", merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(referencesIn(result)).containsExactlyInAnyOrder(aPendingRef, aCapturedRef);
    }

    @Test
    void theStatusFilterIsAlsoScopedToTheMerchant() throws Exception {
        MvcResult result = get("/api/v1/orders?status=PAYMENT_CAPTURED&size=100", merchantToken(merchantA));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(referencesIn(result)).containsExactly(aCapturedRef);
    }

    @Test
    void aMerchantCanReadItsOwnOrderById() throws Exception {
        assertThat(get("/api/v1/orders/" + aCapturedId, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aMerchantCannotReadAnotherMerchantsOrderById() throws Exception {
        assertThat(get("/api/v1/orders/" + bCapturedId, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aMerchantCannotReadAnotherMerchantsOrderByReference() throws Exception {
        assertThat(get("/api/v1/orders/ref/" + bCapturedRef, merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aMerchantUserWithoutAMerchantSeesNothing() throws Exception {
        String orphan = jwtService.createToken(UUID.randomUUID(), "orphan@example.com",
                List.of("MERCHANT_ADMIN"));

        MvcResult list = get("/api/v1/orders?size=100", orphan);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(referencesIn(list)).isEmpty();
        assertThat(get("/api/v1/orders/" + aCapturedId, orphan).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void platformStaffCanReadOrdersOfEveryMerchant() throws Exception {
        String staff = platformToken();

        assertThat(get("/api/v1/orders/" + aCapturedId, staff).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/v1/orders/" + bCapturedId, staff).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aMerchantAdminCannotReachOtherStaffData() throws Exception {
        String token = merchantToken(merchantA);

        assertThat(get("/api/v1/payments", token).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/ledger/accounts", token).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/dashboard/stats", token).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/audit-logs", token).getResponse().getStatus()).isEqualTo(403);
    }
}