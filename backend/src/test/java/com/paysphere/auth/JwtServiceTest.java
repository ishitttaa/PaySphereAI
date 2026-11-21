package com.paysphere.auth;

import com.paysphere.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    @Test
    void tokenRoundTrip() {
        JwtService jwt = new JwtService(SECRET, 900);
        UUID id = UUID.randomUUID();
        Claims claims = jwt.parse(jwt.createToken(id, "a@b.com", List.of("PLATFORM_ADMIN")));
        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("PLATFORM_ADMIN");
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> new JwtService("too-short", 900))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, 900);
        String token = jwt.createToken(UUID.randomUUID(), "a@b.com", List.of());
        assertThatThrownBy(() -> jwt.parse(token + "x")).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, -60);
        String token = jwt.createToken(UUID.randomUUID(), "a@b.com", List.of());
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService other = new JwtService("another-secret-another-secret-another-9999", 900);
        String token = other.createToken(UUID.randomUUID(), "a@b.com", List.of());
        assertThatThrownBy(() -> new JwtService(SECRET, 900).parse(token))
                .isInstanceOf(JwtException.class);
    }
}