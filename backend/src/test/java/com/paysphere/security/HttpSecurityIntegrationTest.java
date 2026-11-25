package com.paysphere.security;

import com.paysphere.BaseIntegrationTest;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Checks the access rules of SecurityConfig through real HTTP calls against the real filter chain.
 * If this test fails, a route is more open or more closed than the table in the README says.
 * Do not "fix" a failure by loosening the test; find out why the route changed.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"SECURITY_MODE=strict", "APP_ENV=development"})
class HttpSecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    @Value("${JWT_SECRET}")
    private String jwtSecret;

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}")
    private String corsOrigins;

    private static final String ID = "00000000-0000-0000-0000-000000000001";

    private static final List<String> PUBLIC = List.of(
            "GET /api/v1/health",
            "GET /api/v1/products",
            "POST /api/v1/orders",
            "POST /api/v1/payments/verify",
            "POST /api/v1/auth/login",
            "POST /api/v1/webhooks/razorpay");

    private static final List<String> STAFF = List.of(
            "GET /api/v1/orders",
            "GET /api/v1/orders/" + ID,
            "GET /api/v1/payments",
            "GET /api/v1/refunds/" + ID,
            "GET /api/v1/ledger/accounts",
            "GET /api/v1/settlements",
            "GET /api/v1/risk-flags",
            "GET /api/v1/dashboard/summary");

    private static final List<String> ADMIN = List.of(
            "POST /api/v1/refunds",
            "POST /api/v1/settlements/record",
            "POST /api/v1/risk-flags/" + ID + "/dismiss",
            "GET /api/v1/audit-logs",
            "GET /api/v1/audit-logs/entity/Payment/" + ID);

    /** Not listed in SecurityConfig, so closed to everybody, admin included. */
    private static final List<String> CLOSED = List.of(
            "GET /api/v1/anything-not-listed",
            "DELETE /api/v1/orders/" + ID,
            "PUT /api/v1/refunds/" + ID);

    private enum Outcome { UNAUTHENTICATED, FORBIDDEN, REACHED_APP }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String tokenWith(String... roles) {
        return jwtService.createToken(UUID.randomUUID(), "tester@example.com", List.of(roles));
    }

    private String admin() {
        return tokenWith("PLATFORM_ADMIN");
    }

    private String operator() {
        return tokenWith("PLATFORM_OPERATOR");
    }

    private Outcome call(String route, String token) throws Exception {
        String[] parts = route.split(" ", 2);
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(parts[0]), parts[1])
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        try {
            MvcResult result = mockMvc.perform(req).andReturn();
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();
            if (status == 401 && body.contains("Authentication required")) {
                return Outcome.UNAUTHENTICATED;
            }
            if (status == 403 && body.contains("Not allowed for your role")) {
                return Outcome.FORBIDDEN;
            }
            return Outcome.REACHED_APP;
        } catch (ServletException e) {
            // It got past security and failed inside the application: security did not block it.
            return Outcome.REACHED_APP;
        }
    }

    private void expectAll(List<String> routes, String token, Outcome expected) throws Exception {
        for (String route : routes) {
            assertThat(call(route, token)).as(route).isEqualTo(expected);
        }
    }

    // ── public routes ────────────────────────────────────────────────────────

    @Test
    void publicRoutesAreOpenToEveryone() throws Exception {
        expectAll(PUBLIC, null, Outcome.REACHED_APP);
        expectAll(PUBLIC, operator(), Outcome.REACHED_APP);
        expectAll(PUBLIC, admin(), Outcome.REACHED_APP);
    }

    // ── staff routes ─────────────────────────────────────────────────────────

    @Test
    void staffRoutesNeedALogin() throws Exception {
        expectAll(STAFF, null, Outcome.UNAUTHENTICATED);
    }

    @Test
    void staffRoutesAreOpenToOperatorAndAdmin() throws Exception {
        expectAll(STAFF, operator(), Outcome.REACHED_APP);
        expectAll(STAFF, admin(), Outcome.REACHED_APP);
    }

    // ── admin-only routes ────────────────────────────────────────────────────

    @Test
    void adminRoutesRejectAnonymousCallers() throws Exception {
        expectAll(ADMIN, null, Outcome.UNAUTHENTICATED);
    }

    @Test
    void adminRoutesRejectOperators() throws Exception {
        expectAll(ADMIN, operator(), Outcome.FORBIDDEN);
    }

    @Test
    void adminRoutesAreOpenToAdmins() throws Exception {
        expectAll(ADMIN, admin(), Outcome.REACHED_APP);
    }

    // ── deny by default ──────────────────────────────────────────────────────

    @Test
    void unlistedRoutesAreClosedEvenToAdmins() throws Exception {
        expectAll(CLOSED, null, Outcome.UNAUTHENTICATED);
        expectAll(CLOSED, operator(), Outcome.FORBIDDEN);
        expectAll(CLOSED, admin(), Outcome.FORBIDDEN);
    }

    // ── bad or unsuitable tokens ─────────────────────────────────────────────

    @Test
    void badTokensAreTreatedLikeNoLogin() throws Exception {
        String garbage = "not-a-real-token";
        String expired = new JwtService(jwtSecret, -60)
                .createToken(UUID.randomUUID(), "tester@example.com", List.of("PLATFORM_ADMIN"));
        String wrongSecret = new JwtService("another-secret-another-secret-another-9999", 900)
                .createToken(UUID.randomUUID(), "tester@example.com", List.of("PLATFORM_ADMIN"));

        for (String token : List.of(garbage, expired, wrongSecret)) {
            expectAll(STAFF, token, Outcome.UNAUTHENTICATED);
            expectAll(ADMIN, token, Outcome.UNAUTHENTICATED);
        }
    }

    @Test
    void aLoggedInUserWithoutAStaffRoleIsForbidden() throws Exception {
        expectAll(STAFF, tokenWith(), Outcome.FORBIDDEN);
        expectAll(STAFF, tokenWith("MERCHANT_ADMIN"), Outcome.FORBIDDEN);
        expectAll(ADMIN, tokenWith("MERCHANT_ADMIN"), Outcome.FORBIDDEN);
    }

    // ── CORS ─────────────────────────────────────────────────────────────────

    @Test
    void corsAllowsOnlyTheConfiguredOrigin() throws Exception {
        String allowed = corsOrigins.split(",")[0].trim();

        mockMvc.perform(get("/api/v1/products").header("Origin", allowed))
                .andExpect(header().string("Access-Control-Allow-Origin", allowed));

        mockMvc.perform(get("/api/v1/products").header("Origin", "http://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @Test
    void refundsRequireAnIdempotencyKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + ID + "\",\"reason\":\"test\"}")
                        .header("Authorization", "Bearer " + admin()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Idempotency-Key");
    }
}