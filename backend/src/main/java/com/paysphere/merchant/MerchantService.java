package com.paysphere.merchant;

import com.paysphere.audit.service.AuditService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.merchant.MerchantDtos.CreateMerchantAdminRequest;
import com.paysphere.merchant.MerchantDtos.MerchantAdminResponse;
import com.paysphere.merchant.MerchantDtos.MerchantResponse;
import com.paysphere.user.entity.User;
import com.paysphere.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    /** Platform admin creates a merchant. The audit entry is written in the same transaction. */
    @Transactional
    public MerchantResponse createMerchant(String rawName) {
        String name = rawName.trim();
        if (merchantRepository.existsByName(name)) {
            throw new BusinessException("MERCHANT_EXISTS", "A merchant with this name already exists");
        }
        Merchant merchant = merchantRepository.saveAndFlush(Merchant.builder().name(name).build());
        auditService.record("MERCHANT_CREATED", "Merchant", merchant.getId(),
                "Merchant created: " + name, "SYSTEM");
        return toResponse(merchant);
    }

    /**
     * Platform admin creates the first (or another) admin user of a merchant.
     * The user, the MERCHANT_ADMIN role and the audit entry are saved together or not at all.
     * The password is only ever stored as a BCrypt hash and is never returned or logged.
     */
    @Transactional
    public MerchantAdminResponse createMerchantAdmin(UUID merchantId, CreateMerchantAdminRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));
        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new BusinessException("MERCHANT_NOT_ACTIVE", "This merchant is not active");
        }

        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("EMAIL_TAKEN", "A user with this email already exists");
        }

        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .merchantId(merchantId)
                .build());

        int assigned = jdbc.update("INSERT INTO user_role_assignments (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE name = 'MERCHANT_ADMIN'", user.getId());
        if (assigned != 1) {
            throw new IllegalStateException("MERCHANT_ADMIN role is missing");
        }

        auditService.record("MERCHANT_ADMIN_CREATED", "User", user.getId(),
                "Merchant admin created for merchant " + merchantId + ": " + email, "SYSTEM");
        return new MerchantAdminResponse(user.getId(), email, merchantId);
    }

    @Transactional(readOnly = true)
    public List<MerchantResponse> listMerchants() {
        return merchantRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(merchant.getId(), merchant.getName(),
                merchant.getStatus(), merchant.getCreatedAt());
    }
}