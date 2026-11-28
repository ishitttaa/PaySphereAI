package com.paysphere.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The tenant-isolation rule in one place: only platform staff are unrestricted. */
class MerchantScopeTest {

    private static final UUID NOBODY = new UUID(0L, 0L);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(UUID merchantId, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new MerchantAuthenticationToken("user@example.com", authorities, merchantId));
    }

    @Test
    void platformAdminIsUnrestricted() {
        login(null, "ROLE_PLATFORM_ADMIN");

        assertThat(MerchantScope.current()).isEmpty();
    }

    @Test
    void platformOperatorIsUnrestricted() {
        login(null, "ROLE_PLATFORM_OPERATOR");

        assertThat(MerchantScope.current()).isEmpty();
    }

    @Test
    void aMerchantAdminIsLimitedToItsOwnMerchant() {
        UUID merchant = UUID.randomUUID();
        login(merchant, "ROLE_MERCHANT_ADMIN");

        assertThat(MerchantScope.current()).contains(merchant);
    }

    @Test
    void aMerchantAdminWithoutAMerchantMatchesNothing() {
        login(null, "ROLE_MERCHANT_ADMIN");

        assertThat(MerchantScope.current()).contains(NOBODY);
    }

    @Test
    void anUnknownRoleIsNeverUnrestricted() {
        login(null, "ROLE_SOMETHING_ELSE");

        assertThat(MerchantScope.current()).contains(NOBODY);
    }

    @Test
    void aMerchantIdOnAPlatformStaffTokenDoesNotRestrictThem() {
        login(UUID.randomUUID(), "ROLE_PLATFORM_ADMIN");

        assertThat(MerchantScope.current()).isEmpty();
    }

    @Test
    void anAnonymousCallerMatchesNothing() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(MerchantScope.current()).contains(NOBODY);
    }

    @Test
    void nobodyLoggedInMatchesNothing() {
        assertThat(MerchantScope.current()).contains(NOBODY);
    }
}