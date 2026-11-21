package com.paysphere.auth;

import com.paysphere.security.JwtService;
import com.paysphere.user.entity.User;
import com.paysphere.user.entity.UserStatus;
import com.paysphere.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtService jwtService =
            new JwtService("test-secret-test-secret-test-secret-1234", 900);
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, encoder, jwtService);
    }

    private User user(UserStatus status) {
        return User.builder().id(userId).email("admin@example.com")
                .passwordHash(encoder.encode("CorrectHorse-123")).fullName("Admin")
                .status(status).build();
    }

    @Test
    void correctPasswordReturnsTokenWithRoles() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(userRepository.findRoleNames(userId)).thenReturn(List.of("PLATFORM_ADMIN"));

        LoginResponse r = authService.login(" Admin@Example.com ", "CorrectHorse-123");

        assertThat(r.roles()).containsExactly("PLATFORM_ADMIN");
        assertThat(jwtService.parse(r.accessToken()).getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void wrongPasswordIsRejected() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        assertThatThrownBy(() -> authService.login("admin@example.com", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknownEmailIsRejected() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login("nobody@example.com", "whatever"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void suspendedUserIsRejectedEvenWithCorrectPassword() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user(UserStatus.SUSPENDED)));
        assertThatThrownBy(() -> authService.login("admin@example.com", "CorrectHorse-123"))
                .isInstanceOf(BadCredentialsException.class);
    }
}