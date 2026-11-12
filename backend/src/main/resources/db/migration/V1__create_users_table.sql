-- ============================================================
-- V1__create_users_table.sql
-- PaySphere Milestone 1 — First Schema Migration
--
-- FLYWAY NAMING RULES (critical — wrong name = migration ignored):
--   V{version}__{description}.sql
--   ↑ Two underscores between version and description
--   Version must be unique and increasing (V1, V2, V3 ...)
--
-- This file runs ONCE when the application starts.
-- After it runs, Flyway records a checksum in flyway_schema_history.
-- If you modify this file after it has run, Flyway will FAIL
-- with a checksum mismatch (intentional — migrations are immutable).
-- To change the schema, create a NEW migration file (V2__...).
-- ============================================================

-- ── Users table ──────────────────────────────────────────────────
-- Stores PaySphere admin and merchant users.
-- Customers who make payments are NOT stored here — only platform users.
CREATE TABLE IF NOT EXISTS users (

    -- UUID primary key: globally unique, not guessable from sequential IDs
    -- This matters for security: user ID 1, 2, 3 is trivially enumerable
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Email is the login identifier — must be unique across all users
    email           VARCHAR(255)    NOT NULL,

    -- Hashed password: NEVER store plain text. Spring Security will use BCrypt.
    -- The column stores the BCrypt hash (always ~60 chars), not the real password.
    password_hash   VARCHAR(255)    NOT NULL,

    -- Human-readable display name
    full_name       VARCHAR(100)    NOT NULL,

    -- Account status: only ACTIVE users can log in
    -- Using VARCHAR instead of ENUM for easier future extension
    -- Valid values: ACTIVE, INACTIVE, SUSPENDED
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    -- Soft timestamps: created once, never changed
    -- TIMESTAMPTZ = timestamp WITH time zone — always store UTC
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Unique constraint on email: prevents duplicate accounts
-- This also creates an index automatically, speeding up login queries
ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email);

-- Check constraint: status must be one of the documented values
ALTER TABLE users
    ADD CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'));

-- ── Roles table ───────────────────────────────────────────────────
-- Simple role definitions. User-role mapping is in user_role_assignments.
-- Starting with two roles: PLATFORM_ADMIN (full access) and MERCHANT_ADMIN.
CREATE TABLE IF NOT EXISTS roles (

    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50)     NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE roles
    ADD CONSTRAINT uq_roles_name UNIQUE (name);

-- ── User-Role join table ──────────────────────────────────────────
-- Many-to-many: one user can have multiple roles; one role can apply to many users.
CREATE TABLE IF NOT EXISTS user_role_assignments (

    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     UUID            NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    assigned_by UUID            REFERENCES users(id) ON DELETE SET NULL,

    -- Prevent the same role being assigned twice to the same user
    CONSTRAINT uq_user_role UNIQUE (user_id, role_id)
);

-- Index for fast lookups of "what roles does user X have?"
CREATE INDEX IF NOT EXISTS idx_user_role_assignments_user_id
    ON user_role_assignments(user_id);

-- ── Seed data: default roles ──────────────────────────────────────
-- We insert the two roles that the application expects to exist.
-- DO NOT insert actual user accounts here — use a secure setup process.
INSERT INTO roles (name, description)
VALUES
    ('PLATFORM_ADMIN',  'Full access to all PaySphere features and configuration'),
    ('MERCHANT_ADMIN',  'Access to payment, refund, and reporting features for own merchant')
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- Verification (you can run these manually in psql to confirm):
--   \dt                           → list all tables
--   \d users                      → describe users table
--   SELECT * FROM roles;          → see seeded roles
--   SELECT * FROM flyway_schema_history;  → migration history
-- ============================================================
