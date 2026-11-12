package com.paysphere.user.entity;

/**
 * User account status values.
 *
 * These MUST match the CHECK constraint in V1__create_users_table.sql:
 *   CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
 *
 * If you add a new status here, you must also create a Flyway migration
 * to update the CHECK constraint in PostgreSQL.
 */
public enum UserStatus {
    /** Normal operating account — can log in and use the system. */
    ACTIVE,

    /** Deactivated by admin or self — cannot log in. */
    INACTIVE,

    /** Suspended due to policy violation — cannot log in, requires review. */
    SUSPENDED
}
