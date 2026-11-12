package com.paysphere.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new business order.
 *
 * CRITICAL: No amount field here. The amount is looked up from the
 * products table server-side using productCode.
 *
 * This is a Java record — immutable, compact, with auto-generated
 * constructor, getters, equals, hashCode, and toString.
 */
public record CreateOrderRequest(

        /**
         * The product code to purchase.
         * Backend looks up the price from the products table.
         * Frontend CANNOT influence the price.
         */
        @NotBlank(message = "Product code is required")
        @Size(max = 100, message = "Product code must not exceed 100 characters")
        String productCode,

        // Customer details — optional but useful for Razorpay prefill
        @Size(max = 100, message = "Customer name must not exceed 100 characters")
        String customerName,

        @Email(message = "Customer email must be a valid email address")
        @Size(max = 255)
        String customerEmail,

        @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Customer phone must be 10-15 digits")
        String customerPhone
) {}
