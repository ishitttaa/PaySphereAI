-- ============================================================
-- V5__create_products_and_risk_tables.sql
-- PaySphere — Products catalogue and Risk flags
-- ============================================================

-- ── Products (price catalogue — amount never trusted from frontend) ─
CREATE TABLE IF NOT EXISTS products (

    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code    VARCHAR(100)    NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,

    -- Price in paise (the ONLY source of truth for price)
    price_paise     BIGINT          NOT NULL,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Is this product currently available for purchase?
    active          BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE products
    ADD CONSTRAINT uq_products_code UNIQUE (product_code);

ALTER TABLE products
    ADD CONSTRAINT chk_products_price_positive
    CHECK (price_paise > 0);

-- Seed a sample product for testing
INSERT INTO products (product_code, name, description, price_paise)
VALUES
    ('PAYSPHERE-BASIC',    'PaySphere Basic Plan',    'Basic monthly subscription',   99900),
    ('PAYSPHERE-PRO',      'PaySphere Pro Plan',      'Pro monthly subscription',     299900),
    ('PAYSPHERE-DEMO',     'Demo Product',             'Test product for development', 50000)
ON CONFLICT (product_code) DO NOTHING;

-- ── Risk Flags ────────────────────────────────────────────────────
-- Deterministic risk rules flag suspicious payment patterns.
CREATE TABLE IF NOT EXISTS risk_flags (

    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The flagged entity
    entity_type         VARCHAR(30)     NOT NULL,   -- 'Payment', 'BusinessOrder'
    entity_id           UUID            NOT NULL,

    -- Rule that triggered the flag
    rule_code           VARCHAR(50)     NOT NULL,   -- e.g., 'HIGH_VELOCITY', 'AMOUNT_THRESHOLD'
    severity            VARCHAR(10)     NOT NULL,   -- 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'

    -- Human-readable explanation
    description         VARCHAR(500)    NOT NULL,

    -- Resolution
    status              VARCHAR(20)     NOT NULL DEFAULT 'OPEN',  -- OPEN | REVIEWED | DISMISSED
    reviewed_by         UUID            REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at         TIMESTAMPTZ,
    review_notes        VARCHAR(500),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE risk_flags
    ADD CONSTRAINT chk_risk_flags_severity
    CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

ALTER TABLE risk_flags
    ADD CONSTRAINT chk_risk_flags_status
    CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED'));

CREATE INDEX IF NOT EXISTS idx_risk_flags_entity
    ON risk_flags(entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_risk_flags_status
    ON risk_flags(status);

CREATE INDEX IF NOT EXISTS idx_risk_flags_severity
    ON risk_flags(severity);
