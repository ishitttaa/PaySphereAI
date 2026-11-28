package com.paysphere.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.security.JwtService;
import io.jsonwebtoken.Claims;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** N9 step 4b: platform admin creates a merchant admin, who logs in and sees only own orders. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantAdminOnboardingIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "Correct-Horse-Battery-9";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    // ── helpers ──────────────────────────────────────────────────────────────

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String token(String role) {
        return jwtService.createToken(UUID.randomUUID(), "t@example.com", List.of(role));
    }

    private UUID createMerchant() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name) values (?, ?)", id, "Admin onboarding " + id);
        return id;
    }

    private void insertOrder(UUID merchantId, String reference) {
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'ADM-PRODUCT', 'Admin test', 1000, 'PENDING')",
                UUID.randomUUID(), merchantId, reference);
    }

    private MvcResult createAdmin(String token, UUID merchantId, String email, String password) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .post("/api/v1/merchants/" + merchantId + "/admins")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"fullName\":\"Shop Owner\",\"password\":\"" + password + "\"}");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    private String newEmail() {
        return "owner-" + shortId() + "@example.com";
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aPlatformAdminCreatesAMerchantAdminSafely() throws Exception {
        UUID merchantId = createMerchant();
        String email = newEmail();

        MvcResult result = createAdmin(token("PLATFORM_ADMIN"), merchantId, email, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD).doesNotContain("$2");
        JsonNode data = objectMapper.readTree(body).get("data");
        UUID userId = UUID.fromString(data.get("userId").asText());

        assertThat(jdbc.queryForObject("select merchant_id from users where id = ?", UUID.class, userId))
                .isEqualTo(merchantId);
        assertThat(jdbc.queryForObject("select count(*) from user_role_assignments a "
                + "join roles r on r.id = a.role_id where a.user_id = ? and r.name = 'MERCHANT_ADMIN'",
                Integer.class, userId)).isEqualTo(1);
        String hash = jdbc.queryForObject("select password_hash from users where id = ?", String.class, userId);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'MERCHANT_ADMIN_CREATED' and entity_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void theNewMerchantAdminCanLogInAndTheTokenCarriesTheMerchant() throws Exception {
        UUID merchantId = createMerchant();
        String email = newEmail();
        createAdmin(token("PLATFORM_ADMIN"), merchantId, email, PASSWORD);

        Claims claims = jwtService.parse(login(email, PASSWORD));

        assertThat(claims.get("merchantId", String.class)).isEqualTo(merchantId.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("MERCHANT_ADMIN");
    }

    @Test
    void aLoggedInMerchantAdminSeesOnlyItsOwnOrders() throws Exception {
        UUID merchantId = createMerchant();
        UUID otherMerchantId = createMerchant();
        String ownRef = "ORD-ADM-" + shortId();
        String otherRef = "ORD-ADM-" + shortId();
        insertOrder(merchantId, ownRef);
        insertOrder(otherMerchantId, otherRef);
        String email = newEmail();
        createAdmin(token("PLATFORM_ADMIN"), merchantId, email, PASSWORD);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/orders?size=100")
                .header("Authorization", "Bearer " + login(email, PASSWORD))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Set<String> refs = new HashSet<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("content").forEach(n -> refs.add(n.get("orderReference").asText()));
        assertThat(refs).containsExactly(ownRef);
    }

    @Test
    void aDuplicateEmailIsRejected() throws Exception {
        UUID merchantId = createMerchant();
        String email = newEmail();
        createAdmin(token("PLATFORM_ADMIN"), merchantId, email, PASSWORD);

        assertThat(createAdmin(token("PLATFORM_ADMIN"), merchantId, email, PASSWORD)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aShortPasswordIsRejected() throws Exception {
        assertThat(createAdmin(token("PLATFORM_ADMIN"), createMerchant(), newEmail(), "short")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anUnknownMerchantIsNotFound() throws Exception {
        assertThat(createAdmin(token("PLATFORM_ADMIN"), UUID.randomUUID(), newEmail(), PASSWORD)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aSuspendedMerchantCannotGetNewAdmins() throws Exception {
        UUID merchantId = createMerchant();
        jdbc.update("update merchants set status = 'SUSPENDED' where id = ?", merchantId);

        assertThat(createAdmin(token("PLATFORM_ADMIN"), merchantId, newEmail(), PASSWORD)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void onlyAPlatformAdminMayCreateMerchantAdmins() throws Exception {
        UUID merchantId = createMerchant();

        assertThat(createAdmin(token("PLATFORM_OPERATOR"), merchantId, newEmail(), PASSWORD)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(createAdmin(token("MERCHANT_ADMIN"), merchantId, newEmail(), PASSWORD)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(createAdmin(null, merchantId, newEmail(), PASSWORD)
                .getResponse().getStatus()).isEqualTo(401);
    }
}