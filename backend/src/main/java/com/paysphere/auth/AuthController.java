package com.paysphere.auth;

import com.paysphere.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_FAILURES_PER_IP_AND_EMAIL = 5;
    private static final int MAX_FAILURES_PER_IP = 20;

    private final AuthService authService;
    private final LoginAttemptLimiter limiter;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {

        String ip = http.getRemoteAddr();
        String email = request.email().trim().toLowerCase();
        String ipKey = "ip:" + ip;
        String pairKey = "pair:" + ip + "|" + email;

        if (limiter.isBlocked(ipKey, MAX_FAILURES_PER_IP)
                || limiter.isBlocked(pairKey, MAX_FAILURES_PER_IP_AND_EMAIL)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "900")
                    .body(ApiResponse.failure("Too many failed attempts. Try again later."));
        }

        try {
            LoginResponse response = authService.login(request.email(), request.password());
            limiter.recordSuccess(pairKey);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (BadCredentialsException e) {
            limiter.recordFailure(ipKey);
            limiter.recordFailure(pairKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.failure("Invalid email or password"));
        }
    }
}