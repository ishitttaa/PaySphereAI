package com.paysphere.webhook;

import com.paysphere.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class WebhookFailurePersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private int deliverFailingWebhook(String eventId) throws Exception {
        String json = "{\"eventType\":\"refund.processed\",\"eventId\":\"" + eventId + "\","
                + "\"refundId\":\"rfnd_test_" + shortId() + "\","
                + "\"paymentId\":\"pay_never_recorded_" + shortId() + "\","
                + "\"amountPaise\":5000}";
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/simulator/webhook")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    void aFailedWebhookIsStoredAsFailedAndTheSenderGetsAnError() throws Exception {
        String eventId = "evt_test_" + shortId();

        int status = deliverFailingWebhook(eventId);

        assertThat(status).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where razorpay_event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from webhook_events where razorpay_event_id = ?",
                String.class, eventId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select error_message from webhook_events where razorpay_event_id = ?",
                String.class, eventId)).isNotBlank();
    }

    @Test
    void aFailedWebhookIsRetriedAndStaysASingleRow() throws Exception {
        String eventId = "evt_test_" + shortId();

        deliverFailingWebhook(eventId);
        deliverFailingWebhook(eventId);

        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where razorpay_event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from webhook_events where razorpay_event_id = ?",
                String.class, eventId)).isEqualTo("FAILED");
    }
}