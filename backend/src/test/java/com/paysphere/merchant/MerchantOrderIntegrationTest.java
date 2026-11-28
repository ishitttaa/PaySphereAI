package com.paysphere.merchant;

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

/** N9: only platform staff can create and list merchants. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantOnboardingIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private String token(String role) {
        return jwtService.createToken(UUID.randomUUID(), "t@example.com", List.of(role));
    }

    private String uniqueName() {
        return "Onboarding " + UUID.randomUUID();
    }

    private MvcResult create(String token, String name) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult list(String token) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/api/v1/merchants");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void aPlatformAdminCreatesAMerchantAndItIsAudited() throws Exception {
        String name = uniqueName();

        MvcResult result = create(token("PLATFORM_ADMIN"), name);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        UUID id = UUID.fromString(data.get("id").asText());
        assertThat(data.get("name").asText()).isEqualTo(name);
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from merchants where id = ?", String.class, id))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'MERCHANT_CREATED' and entity_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void aDuplicateMerchantNameIsRejected() throws Exception {
        String name = uniqueName();
        create(token("PLATFORM_ADMIN"), name);

        assertThat(create(token("PLATFORM_ADMIN"), name).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aBlankMerchantNameIsRejected() throws Exception {
        assertThat(create(token("PLATFORM_ADMIN"), "   ").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anOperatorCannotCreateAMerchant() throws Exception {
        assertThat(create(token("PLATFORM_OPERATOR"), uniqueName()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aMerchantAdminCannotCreateOrListMerchants() throws Exception {
        String merchantAdmin = token("MERCHANT_ADMIN");

        assertThat(create(merchantAdmin, uniqueName()).getResponse().getStatus()).isEqualTo(403);
        assertThat(list(merchantAdmin).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anAnonymousCallerCannotCreateOrListMerchants() throws Exception {
        assertThat(create(null, uniqueName()).getResponse().getStatus()).isEqualTo(401);
        assertThat(list(null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void platformStaffCanListMerchants() throws Exception {
        String name = uniqueName();
        create(token("PLATFORM_ADMIN"), name);

        for (String role : List.of("PLATFORM_ADMIN", "PLATFORM_OPERATOR")) {
            MvcResult result = list(token(role));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).contains(name);
        }
    }
}