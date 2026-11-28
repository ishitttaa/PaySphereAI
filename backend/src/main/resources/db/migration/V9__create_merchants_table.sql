-- N9 step 1: merchants (tenants). Users may belong to one merchant.
-- NULL merchant_id = platform staff (PLATFORM_ADMIN / PLATFORM_OPERATOR).
CREATE TABLE IF NOT EXISTS merchants (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchants_name UNIQUE (name),
    CONSTRAINT chk_merchants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS merchant_id UUID REFERENCES merchants(id);

CREATE INDEX IF NOT EXISTS idx_users_merchant_id ON users(merchant_id);