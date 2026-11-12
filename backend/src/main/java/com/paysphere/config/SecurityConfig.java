package com.paysphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security Configuration — Milestone 1 baseline.
 *
 * In Milestone 1 we permit all requests so we can test the health endpoint
 * without authentication. We will replace this with JWT-based security
 * in a later milestone (Milestone 9: Authentication & Authorization).
 *
 * WHY configure this at all now?
 * Spring Boot auto-configures Basic Auth by default if no SecurityFilterChain
 * is provided. That would block our /api/v1/health test. We define an explicit
 * chain that permits everything for now — and we'll tighten it incrementally.
 *
 * IMPORTANT: The comment "MILESTONE 1 — OPEN" is a deliberate marker.
 * When we reach the auth milestone, we search for this marker and replace it.
 * This prevents accidentally shipping an open configuration to production.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless REST API (we'll use JWT, not cookies)
            .csrf(AbstractHttpConfigurer::disable)

            // Enable CORS for frontend applications
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Stateless — no HTTP sessions. Every request must authenticate itself.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // MILESTONE 1 — OPEN: permit all endpoints while we build the foundation.
            // This MUST be replaced with proper JWT authorization in Milestone 9.
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.List.of("*"));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(java.util.List.of("*"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
