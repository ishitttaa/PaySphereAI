package com.paysphere.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** N9 step 3: the merchant travels inside the signed login token and cannot be forged. */
class MerchantTokenTest {

    private static final String SECRET = "merchant_test_secret_that_is_long_enough_123456";
    private final JwtService jwt = new JwtService(SECRET, 900);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void runFilter(String bearerToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + bearerToken);
        new JwtAuthFilter(jwt).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void theTokenCarriesTheMerchantId() {
        UUID merchantId = UUID.randomUUID();
        Claims claims = jwt.parse(jwt.createToken(
                UUID.randomUUID(), "m@example.com", List.of("MERCHANT_ADMIN"), merchantId));

        assertThat(claims.get("merchantId", String.class)).isEqualTo(merchantId.toString());
    }

    @Test
    void aTokenWithoutMerchantHasNoMerchantClaim() {
        Claims claims = jwt.parse(jwt.createToken(
                UUID.randomUUID(), "staff@example.com", List.of("PLATFORM_ADMIN")));

        assertThat(claims.get("merchantId")).isNull();
    }

    @Test
    void currentUserExposesTheMerchantFromTheToken() throws Exception {
        UUID merchantId = UUID.randomUUID();
        runFilter(jwt.createToken(UUID.randomUUID(), "m@example.com",
                List.of("MERCHANT_ADMIN"), merchantId));

        assertThat(CurrentUser.merchantId()).contains(merchantId);
    }

    @Test
    void platformStaffHaveNoMerchant() throws Exception {
        runFilter(jwt.createToken(UUID.randomUUID(), "staff@example.com", List.of("PLATFORM_ADMIN")));

        assertThat(CurrentUser.email()).contains("staff@example.com");
        assertThat(CurrentUser.merchantId()).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejectedEvenWithAMerchantId() throws Exception {
        JwtService attacker = new JwtService("attacker_secret_that_is_also_long_enough_9876", 900);
        runFilter(attacker.createToken(UUID.randomUUID(), "evil@example.com",
                List.of("MERCHANT_ADMIN"), UUID.randomUUID()));

        assertThat(CurrentUser.email()).isEmpty();
        assertThat(CurrentUser.merchantId()).isEmpty();
    }
}