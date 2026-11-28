package com.paysphere.config;

import com.paysphere.security.JwtAuthFilter;
import com.paysphere.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ADMIN = "PLATFORM_ADMIN";
    private static final String OPERATOR = "PLATFORM_OPERATOR";

    @Value("${SECURITY_MODE:strict}")
    private String securityMode;

    @Value("${APP_ENV:development}")
    private String appEnv;

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        boolean production = "production".equalsIgnoreCase(appEnv);
        boolean open = "open".equalsIgnoreCase(securityMode);
        if (open && production) {
            throw new IllegalStateException(
                    "FATAL: SECURITY_MODE=open is not allowed when APP_ENV=production");
        }

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "Authentication required"))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "Not allowed for your role")));

        if (open) {
            log.warn("SECURITY_MODE=open: ALL ENDPOINTS ARE PUBLIC. Development only.");
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> {
                // API docs: open while developing, closed in production
                if (production) {
                    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").denyAll();
                } else {
                    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll();
                }

                // Public: no login needed
                auth.requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health", "/actuator/health/**").permitAll();
                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                auth.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/razorpay").permitAll();   // protected by its own HMAC
                auth.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll();
                auth.requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/payments/verify").permitAll();
                auth.requestMatchers("/api/v1/simulator/**").permitAll();   // exists only when PAYMENT_GATEWAY=simulator

                // Admin only
                auth.requestMatchers(HttpMethod.POST, "/api/v1/refunds", "/api/v1/settlements/record",
                        "/api/v1/risk-flags/*/dismiss").hasRole(ADMIN);
                auth.requestMatchers("/api/v1/audit-logs/**").hasRole(ADMIN);
                auth.requestMatchers(HttpMethod.POST, "/api/v1/merchants").hasRole(ADMIN);
                auth.requestMatchers(HttpMethod.POST, "/api/v1/merchants/*/admins").hasRole(ADMIN);                
                auth.requestMatchers(HttpMethod.GET, "/api/v1/merchants").hasAnyRole(ADMIN, OPERATOR);               
                auth.requestMatchers(HttpMethod.POST, "/api/v1/refunds", "/api/v1/settlements/record",
                        "/api/v1/risk-flags/*/dismiss", "/api/v1/reconciliation/run").hasRole(ADMIN);
                
                // Orders: staff see all, a merchant admin sees only their own (filtered in OrderService)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/**")
                        .hasAnyRole(ADMIN, OPERATOR, "MERCHANT_ADMIN");   
                
                // Payments: staff see all, a merchant admin sees only their own (filtered in PaymentService)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/payments", "/api/v1/payments/**")
                        .hasAnyRole(ADMIN, OPERATOR, "MERCHANT_ADMIN");
                
                // Refunds: staff see all, a merchant admin sees only their own (filtered in RefundService)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/refunds/**")
                        .hasAnyRole(ADMIN, OPERATOR, "MERCHANT_ADMIN");

                // Dashboard stats: a merchant admin gets only their own figures (filtered in DashboardController)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/dashboard/stats")
                        .hasAnyRole(ADMIN, OPERATOR, "MERCHANT_ADMIN");

                // Ledger transactions: a merchant admin sees only their own (filtered in LedgerService)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/ledger/transactions")
                        .hasAnyRole(ADMIN, OPERATOR, "MERCHANT_ADMIN");
                    
                // Staff: read-only views
                auth.requestMatchers(HttpMethod.GET, "/api/v1/orders/**",
                         "/api/v1/ledger/**", "/api/v1/settlements/**",
                        "/api/v1/risk-flags/**", "/api/v1/dashboard/**").hasAnyRole(ADMIN, OPERATOR);

                // Anything not listed above is closed
                auth.anyRequest().denyAll();
            });
        }
        return http.build();
    }

    /** Stops Spring creating its own throw-away login user. Login happens only via /api/v1/auth/login. */
    @Bean
    public UserDetailsService noDefaultUsers() {
        return username -> {
            throw new UsernameNotFoundException("Use /api/v1/auth/login");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeJson(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"success\":false,\"error\":\"" + message + "\"}");
    }
}