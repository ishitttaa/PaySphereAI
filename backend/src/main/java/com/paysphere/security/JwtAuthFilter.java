package com.paysphere.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer <token>", and if the token is valid, logs the caller in
 * for this one request. An invalid or missing token simply leaves the caller anonymous;
 * the security rules then answer 401 where login is required.
 *
 * NOT a @Component on purpose: it is added to the security chain by SecurityConfig.
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                List<?> roles = claims.get("roles", List.class);
                List<SimpleGrantedAuthority> authorities = roles == null ? List.of()
                        : roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                claims.get("email", String.class), null, authorities));
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected bearer token: {}", e.getClass().getSimpleName());
            }
        }
        chain.doFilter(request, response);
    }
}