package com.paysphere.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    private static final String ISSUER = "paysphere";

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiry-seconds:900}") long expirySeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirySeconds = expirySeconds;
    }

    /** Token for platform staff (no merchant). */
    public String createToken(UUID userId, String email, List<String> roles) {
        return createToken(userId, email, roles, null);
    }

    /** Token for a user who may belong to a merchant (merchantId null = platform staff). */
    public String createToken(UUID userId, String email, List<String> roles, UUID merchantId) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)));
        if (merchantId != null) {
            builder.claim("merchantId", merchantId.toString());
        }
        return builder.signWith(key).compact();
    }

    /** Throws io.jsonwebtoken.JwtException if the token is forged, tampered or expired. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }
}