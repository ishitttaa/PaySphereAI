-- ============================================================
-- V3__create_payments_table.sql
-- PaySphere Milestone 3–4 — Payments & Payment Lifecycle
--
-- A payment record is created when Razorpay confirms a payment attempt.
-- One business_order can have multiple payment attempts (e.g., card declined,
-- then retried and succeeded). Only one payment per order reaches CAPTURED.
-- ============================================================

CREATE TABLE IF NOT EXISTS payments (

    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Link to the PaySphere business order
    business_order_id       UUID            NOT NULL REFERENCES business_orders(id),

    -- Razorpay identifiers
    razorpay_payment_id     VARCHAR(100)    NOT NULL,
    razorpay_order_id       VARCHAR(100)    NOT NULL,

    -- Payment state machine
    -- CREATED → AUTHORIZED → CAPTURED | FAILED | REFUNDED
    status                  VARCHAR(30)     NOT NULL DEFAULT 'CREATED',

    -- Financial amounts in paise
    amount_paise            BIGINT          NOT NULL,
    amount_refunded_paise   BIGINT          NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Payment method details (non-sensitive — no card numbers ever stored)
    method                  VARCHAR(30),        -- 'card', 'upi', 'netbanking', 'wallet'
    bank                    VARCHAR(100),       -- bank name for netbanking
    wallet                  VARCHAR(50),        -- wallet name
    vpa                     VARCHAR(255),       -- UPI VPA (e.g., user@upi)

    -- Card details (NON-SENSITIVE: last 4 digits, network only — no PAN ever)
    card_last4              VARCHAR(4),
    card_network            VARCHAR(20),        -- 'Visa', 'Mastercard', etc.
    card_issuer             VARCHAR(100),

    -- Failure details
    error_code              VARCHAR(100),
    error_description       VARCHAR(500),
    error_source            VARCHAR(50),
    error_step              VARCHAR(50),
    error_reason            VARCHAR(100),

    -- Razorpay timestamps
    razorpay_created_at     TIMESTAMPTZ,
    razorpay_captured_at    TIMESTAMPTZ,

    -- Idempotency: we record the signature we verified, never trust it on re-read
    -- Stored as SHA-256 hash of the original signature, not the raw value
    signature_verified      BOOLEAN         NOT NULL DEFAULT FALSE,

    -- International fields
    international           BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Timestamps
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Unique constraint on Razorpay payment ID
ALTER TABLE payments
    ADD CONSTRAINT uq_payments_razorpay_payment_id UNIQUE (razorpay_payment_id);

-- Status constraint
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status
    CHECK (status IN (
        'CREATED',
        'AUTHORIZED',
        'CAPTURED',
        'REFUNDED',
        'FAILED'
    ));

-- Amount must be positive
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_amount_positive
    CHECK (amount_paise > 0);

-- Refunded amount cannot exceed total
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_refund_not_exceed_amount
    CHECK (amount_refunded_paise <= amount_paise);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_payments_business_order_id
    ON payments(business_order_id);

CREATE INDEX IF NOT EXISTS idx_payments_razorpay_payment_id
    ON payments(razorpay_payment_id);

CREATE INDEX IF NOT EXISTS idx_payments_status
    ON payments(status);

CREATE INDEX IF NOT EXISTS idx_payments_created_at
    ON payments(created_at DESC);
