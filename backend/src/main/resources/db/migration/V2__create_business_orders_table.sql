-- ============================================================
-- V2__create_business_orders_table.sql
-- PaySphere Milestone 2 — Business Orders Schema
--
-- A business_order represents an intent to pay on PaySphere's side.
-- It is created BEFORE calling Razorpay, so we always have a local
-- record even if the Razorpay API call fails.
--
-- Important: amount is stored in PAISE (smallest INR unit).
-- 1 INR = 100 paise. This avoids floating-point precision issues.
-- ============================================================

CREATE TABLE IF NOT EXISTS business_orders (

    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Short human-readable reference (e.g., "ORD-20261011-001")
    order_reference     VARCHAR(50)     NOT NULL,

    -- Product/service description for ledger and display
    product_id          VARCHAR(100)    NOT NULL,
    product_name        VARCHAR(255)    NOT NULL,

    -- Amount in paise (integer). NEVER in rupees (floating point issues).
    -- Example: Rs 1,000.00 → 100000 paise
    amount_paise        BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Internal payment status state machine
    -- PENDING → PAYMENT_INITIATED → PAYMENT_CAPTURED | PAYMENT_FAILED | PAYMENT_EXPIRED
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',

    -- Razorpay order ID returned after calling POST /v1/orders
    -- NULL until we successfully call Razorpay
    razorpay_order_id   VARCHAR(100),

    -- Customer info (copied at order time for historical accuracy)
    customer_name       VARCHAR(100),
    customer_email      VARCHAR(255),
    customer_phone      VARCHAR(20),

    -- Who created this order (admin user)
    created_by_user_id  UUID            REFERENCES users(id) ON DELETE SET NULL,

    -- Timestamps
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Expiry: Razorpay orders expire after 15 minutes by default
    expires_at          TIMESTAMPTZ
);

-- Unique constraint on order_reference
ALTER TABLE business_orders
    ADD CONSTRAINT uq_business_orders_reference UNIQUE (order_reference);

-- Unique constraint on razorpay_order_id (once set)
ALTER TABLE business_orders
    ADD CONSTRAINT uq_business_orders_razorpay_id UNIQUE (razorpay_order_id);

-- Status check constraint
ALTER TABLE business_orders
    ADD CONSTRAINT chk_business_orders_status
    CHECK (status IN (
        'PENDING',
        'PAYMENT_INITIATED',
        'PAYMENT_CAPTURED',
        'PAYMENT_FAILED',
        'PAYMENT_EXPIRED',
        'REFUNDED',
        'PARTIALLY_REFUNDED'
    ));

-- Amount must be positive
ALTER TABLE business_orders
    ADD CONSTRAINT chk_business_orders_amount_positive
    CHECK (amount_paise > 0);

-- Currency constraint
ALTER TABLE business_orders
    ADD CONSTRAINT chk_business_orders_currency
    CHECK (currency = 'INR');

-- Index for status-based queries (admin dashboard)
CREATE INDEX IF NOT EXISTS idx_business_orders_status
    ON business_orders(status);

-- Index for Razorpay order ID lookups (webhook processing)
CREATE INDEX IF NOT EXISTS idx_business_orders_razorpay_id
    ON business_orders(razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL;

-- Index for time-based queries
CREATE INDEX IF NOT EXISTS idx_business_orders_created_at
    ON business_orders(created_at DESC);
