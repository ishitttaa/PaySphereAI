package com.paysphere.auth;

import com.paysphere.security.JwtService;
import com.paysphere.user.entity.User;
import com.paysphere.user.entity.UserStatus;
import com.paysphere.user.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Checked when the email doesn't exist, so "no such user" and "wrong password"
    // take the same time and reveal nothing.
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        User user = userRepository.findByEmail(normalized).orElse(null);

        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean passwordOk = passwordEncoder.matches(password == null ? "" : password, hash);

        if (user == null || !passwordOk || user.getStatus() != UserStatus.ACTIVE) {
            throw new BadCredentialsException("Invalid email or password");
        }

        List<String> roles = userRepository.findRoleNames(user.getId());
                String token = jwtService.createToken(user.getId(), user.getEmail(), roles, user.getMerchantId());
        return new LoginResponse(token, "Bearer", jwtService.getExpirySeconds(), user.getEmail(), roles);
    }
}