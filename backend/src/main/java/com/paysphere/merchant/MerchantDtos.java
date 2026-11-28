package com.paysphere.merchant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class MerchantDtos {

    private MerchantDtos() {
    }

    public record CreateMerchantRequest(
            @NotBlank(message = "Merchant name is required")
            @Size(max = 200, message = "Merchant name must not exceed 200 characters")
            String name) {
    }

    public record MerchantResponse(UUID id, String name, MerchantStatus status, Instant createdAt) {
    }

    public record CreateMerchantAdminRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            @Size(max = 255, message = "Email must not exceed 255 characters")
            String email,

            @NotBlank(message = "Full name is required")
            @Size(max = 100, message = "Full name must not exceed 100 characters")
            String fullName,

            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 72, message = "Password must be 12 to 72 characters")
            String password) {
    }

    /** Never contains the password or its hash. */
    public record MerchantAdminResponse(UUID userId, String email, UUID merchantId) {
    }
}