package com.paysphere.user.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * User Entity — JPA representation of the `users` PostgreSQL table.
 *
 * Key JPA annotations explained:
 *
 * @Entity       → tells Hibernate this class maps to a database table
 * @Table        → specifies the table name (defaults to class name otherwise)
 * @Id           → marks the primary key field
 * @GeneratedValue → tells JPA how to generate the PK value
 * @Column       → maps the field to a specific column with constraints
 *
 * Lombok annotations:
 * @Getter       → generates getters for all fields
 * @Setter       → generates setters for all fields
 * @Builder      → enables User.builder().email("x").build() pattern
 * @NoArgsConstructor → required by JPA (it needs a no-arg constructor)
 * @AllArgsConstructor → used by @Builder
 *
 * WHY UUID primary key?
 * Sequential integer IDs are guessable. A user whose ID is 42 can guess
 * that user 43 exists. UUIDs are practically unguessable — important when
 * IDs appear in API responses and URLs.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * N9: the merchant this user belongs to. NULL for platform staff
     * (PLATFORM_ADMIN / PLATFORM_OPERATOR).
     */
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /**
     * SECURITY: This field stores the BCrypt hash, NOT the plaintext password.
     * It should NEVER be returned in API responses.
     * We'll annotate it with @JsonIgnore when we build the UserDto.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * @CreationTimestamp → Hibernate sets this automatically on first INSERT.
     * updatable = false → once set, it never changes.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @UpdateTimestamp → Hibernate updates this on every UPDATE automatically.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}