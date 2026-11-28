package com.paysphere.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/** N9 step 2: orders belong to the merchant that owns the product. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantOrderIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void everySeededProductBelongsToAMerchant() {
        assertThat(jdbc.queryForObject(
                "select count(*) from products where merchant_id is null", Integer.class)).isZero();
    }

    @Test
    void anOrderGetsTheMerchantOfItsProduct() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"PAYSPHERE-BASIC\",\"customerName\":\"Tenant Test\","
                        + "\"customerEmail\":\"tenant@example.com\",\"customerPhone\":\"9999999999\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isLessThan(300);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        String razorpayOrderId = data.get("razorpayOrderId").asText();

        String orderMerchant = jdbc.queryForObject(
                "select merchant_id::text from business_orders where razorpay_order_id = ?",
                String.class, razorpayOrderId);
        String productMerchant = jdbc.queryForObject(
                "select merchant_id::text from products where product_code = 'PAYSPHERE-BASIC'",
                String.class);

        assertThat(orderMerchant).isNotNull().isEqualTo(productMerchant);
    }
}