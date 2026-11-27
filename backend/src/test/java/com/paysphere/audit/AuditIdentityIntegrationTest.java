package com.paysphere.audit;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks that the audit trail names the real admin, and that a caller cannot name someone else. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class AuditIdentityIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_EMAIL = "auditor@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

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

    private String adminToken() {
        return jwtService.createToken(UUID.randomUUID(), ADMIN_EMAIL, List.of("PLATFORM_ADMIN"));
    }

    private UUID createCapturedPayment(boolean international) throws Exception {
        JsonNode order = post("/api/v1/orders",
                "{\"productCode\":\"PAYSPHERE-BASIC\",\"customerName\":\"Audit Test\","
                        + "\"customerEmail\":\"audit@example.com\",\"customerPhone\":\"9999999999\"}");
        String orderId = order.get("razorpayOrderId").asText();
        JsonNode pay = post("/api/v1/simulator/pay",
                "{\"razorpayOrderId\":\"" + orderId + "\",\"outcome\":\"SUCCESS\",\"international\":"
                        + international + "}");
        JsonNode payment = post("/api/v1/payments/verify",
                "{\"razorpayPaymentId\":\"" + pay.get("razorpayPaymentId").asText()
                        + "\",\"razorpayOrderId\":\"" + orderId
                        + "\",\"razorpaySignature\":\"" + pay.get("razorpaySignature").asText() + "\"}");
        return UUID.fromString(payment.get("id").asText());
    }

    @Test
    void aRefundIsAuditedUnderTheAdminsEmailWithTheirAddress() throws Exception {
        UUID paymentId = createCapturedPayment(false);

        JsonNode refund = post("/api/v1/refunds",
                "{\"paymentId\":\"" + paymentId + "\",\"amountPaise\":10000,\"reason\":\"audit\"}",
                "Authorization", "Bearer " + adminToken(),
                "Idempotency-Key", "audit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        UUID refundId = UUID.fromString(refund.get("id").asText());

        assertThat(jdbc.queryForObject(
                "select actor_email from audit_logs where action = 'REFUND_PROCESSED' and entity_id = ?",
                String.class, refundId)).isEqualTo(ADMIN_EMAIL);
        assertThat(jdbc.queryForObject(
                "select ip_address from audit_logs where action = 'REFUND_PROCESSED' and entity_id = ?",
                String.class, refundId)).isNotBlank();
        // The customer is not logged in, so the checkout keeps its label.
        assertThat(jdbc.queryForObject(
                "select actor_email from audit_logs where action = 'PAYMENT_CAPTURED' and entity_id = ?",
                String.class, paymentId)).isEqualTo("CHECKOUT");
    }

    @Test
    void dismissingAFlagUsesTheLoginTokenAndIgnoresTheRequestBody() throws Exception {
        UUID paymentId = createCapturedPayment(true);   // an international card raises a flag
        UUID flagId = jdbc.queryForObject(
                "select id from risk_flags where entity_id = ? and rule_code = 'INTERNATIONAL_CARD'",
                UUID.class, paymentId);
        UUID impersonated = UUID.randomUUID();

        post("/api/v1/risk-flags/" + flagId + "/dismiss",
                "{\"notes\":\"checked\",\"reviewerUserId\":\"" + impersonated + "\"}",
                "Authorization", "Bearer " + adminToken());

        assertThat(jdbc.queryForObject("select status from risk_flags where id = ?", String.class, flagId))
                .isEqualTo("DISMISSED");
        assertThat(jdbc.queryForObject("select reviewed_by from risk_flags where id = ?", UUID.class, flagId))
                .isNull();
        assertThat(jdbc.queryForObject("select review_notes from risk_flags where id = ?", String.class, flagId))
                .isEqualTo("checked");
        assertThat(jdbc.queryForObject(
                "select actor_email from audit_logs where action = 'RISK_FLAG_DISMISSED' and entity_id = ?",
                String.class, flagId)).isEqualTo(ADMIN_EMAIL);
    }
}