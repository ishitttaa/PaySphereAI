package com.paysphere.product.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Product entity — represents a purchasable item in the PaySphere catalogue.
 *
 * CRITICAL SECURITY RULE: The price of a product is ONLY read from here.
 * The frontend NEVER sends the price. It sends only the productCode.
 * The backend looks up the price from this table and uses it for the Razorpay order.
 *
 * This prevents price tampering: a customer cannot pay Rs 1 for a Rs 1000 product
 * by modifying the network request.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "product_code", nullable = false, unique = true, length = 100)
    private String productCode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Price in PAISE (smallest INR unit). NEVER store in rupees.
     * Rs 999.00 = 99900 paise.
     * Integer storage avoids floating-point precision issues entirely.
     */
    @Column(name = "price_paise", nullable = false)
    private Long pricePaise;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
