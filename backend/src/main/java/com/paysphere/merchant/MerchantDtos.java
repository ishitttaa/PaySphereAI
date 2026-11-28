package com.paysphere.merchant;

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
}