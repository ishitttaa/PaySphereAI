package com.paysphere.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.gateway.SimulatedGateway;
import com.paysphere.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is shared with other test classes, so we cannot assume absolute numbers.
 * We read the dashboard before, create a known set of payments, refunds and a settlement,
 * read it again, and compare the DIFFERENCE with hand-computed values.
 *
 * BASIC plan = 99900 paise.
 *   2 payments         -> gross    +199800
 *   refunds 20000+5000 -> refunded +25000
 *   net revenue        -> +174800
 *   1 settlement       -> settled  +50000
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class DashboardStatsIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private SimulatedGateway simulatedGateway;

    private String adminToken() {
        return "Bearer " + jwtService.createToken(
                UUID.randomUUID(), "dash@example.com", List.of("PLATFORM_ADMIN"));
    }

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

    private JsonNode stats() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/dashboard/stats")
                .header("Authorization", adminToken())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private UUID createCapturedPayment() throws Exception {
        JsonNode order = post("/api/v1/orders",
                "{\"productCode\":\"PAYSPHERE-BASIC\",\"customerName\":\"Dash Test\","
                        + "\"customerEmail\":\"dash@example.com\",\"customerPhone\":\"9999999999\"}");
        String razorpayOrderId = order.get("razorpayOrderId").asText();
        JsonNode pay = post("/api/v1/simulator/pay",
                "{\"razorpayOrderId\":\"" + razorpayOrderId + "\",\"outcome\":\"SUCCESS\"}");
        JsonNode payment = post("/api/v1/payments/verify",
                "{\"razorpayPaymentId\":\"" + pay.get("razorpayPaymentId").asText()
                        + "\",\"razorpayOrderId\":\"" + razorpayOrderId
                        + "\",\"razorpaySignature\":\"" + pay.get("razorpaySignature").asText() + "\"}");
        return UUID.fromString(payment.get("id").asText());
    }

    private void refund(UUID paymentId, long amountPaise) throws Exception {
        JsonNode refund = post("/api/v1/refunds",
                "{\"paymentId\":\"" + paymentId + "\",\"amountPaise\":" + amountPaise
                        + ",\"reason\":\"dashboard test\"}",
                "Authorization", adminToken(),
                "Idempotency-Key", "dash-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        assertThat(refund.get("status").asText()).isEqualTo("PROCESSED");
    }

    @AfterEach
    void cleanUp() {
        simulatedGateway.setRefundMode(SimulatedGateway.RefundMode.NORMAL);
    }

    @Test
    void dashboardFiguresMatchHandComputedValues() throws Exception {
        JsonNode before = stats();

        UUID p1 = createCapturedPayment();
        UUID p2 = createCapturedPayment();
        refund(p1, 20000L);
        refund(p2, 5000L);
        post("/api/v1/settlements/record",
                "{\"razorpaySettlementId\":\"setl_dash_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                        + "\",\"amountPaise\":50000,\"feesPaise\":100,\"taxPaise\":0,\"utr\":\"UTRDASH1\"}",
                "Authorization", adminToken());

        JsonNode after = stats();

        assertThat(delta(before, after, "grossRevenuePaise")).isEqualTo(199_800L);
        assertThat(delta(before, after, "refundedAmountPaise")).isEqualTo(25_000L);
        assertThat(delta(before, after, "totalRevenuePaise")).isEqualTo(174_800L);
        assertThat(delta(before, after, "successfulPaymentsCount")).isEqualTo(2L);
        assertThat(delta(before, after, "refundsCount")).isEqualTo(2L);
        assertThat(delta(before, after, "settledAmountPaise")).isEqualTo(50_000L);
        assertThat(delta(before, after, "totalOrdersCount")).isEqualTo(2L);
    }

    private long delta(JsonNode before, JsonNode after, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }
}