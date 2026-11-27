package com.paysphere.auth;

import com.paysphere.security.JwtAuthFilter;
import com.paysphere.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private final JwtService jwt = new JwtService(SECRET, 900);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwt);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void validTokenLogsTheCallerInWithRoles() throws Exception {
        String token = jwt.createToken(UUID.randomUUID(), "a@b.com", List.of("PLATFORM_ADMIN"));
        Authentication auth = run("Bearer " + token);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("a@b.com");
        List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(roles).containsExactly("ROLE_PLATFORM_ADMIN");
    }

    @Test
    void missingHeaderLeavesCallerAnonymous() throws Exception {
        assertThat(run(null)).isNull();
    }

    @Test
    void garbageTokenLeavesCallerAnonymous() throws Exception {
        assertThat(run("Bearer not-a-real-token")).isNull();
    }

    @Test
    void expiredTokenLeavesCallerAnonymous() throws Exception {
        String expired = new JwtService(SECRET, -60).createToken(UUID.randomUUID(), "a@b.com", List.of("PLATFORM_ADMIN"));
        assertThat(run("Bearer " + expired)).isNull();
    }
        @Test
    void theUserIdFromTheTokenIsAvailableAsDetails() throws Exception {
        UUID id = UUID.randomUUID();
        Authentication auth = run("Bearer " + jwt.createToken(id, "a@b.com", List.of("PLATFORM_ADMIN")));

        assertThat(auth.getDetails()).isEqualTo(id.toString());
    }
}