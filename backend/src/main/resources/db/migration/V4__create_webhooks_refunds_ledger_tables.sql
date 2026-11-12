-- ============================================================
-- V4__create_webhooks_refunds_ledger_tables.sql
-- PaySphere Milestone 5–7 — Webhooks, Refunds, and Ledger
-- ============================================================

-- ── Webhook Events ────────────────────────────────────────────────
-- Raw webhook payloads from Razorpay, stored for audit and reprocessing.
-- We deduplicate by razorpay_event_id to ensure idempotency.
CREATE TABLE IF NOT EXISTS webhook_events (

    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Razorpay's unique event identifier (deduplicate on this)
    razorpay_event_id   VARCHAR(100)    NOT NULL,

    -- Event type: 'payment.captured', 'payment.failed', 'refund.created', etc.
    event_type          VARCHAR(100)    NOT NULL,

    -- Raw JSON payload from Razorpay (stored for audit/reprocessing)
    payload             TEXT            NOT NULL,

    -- Processing state
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- PENDING → PROCESSED | FAILED | SKIPPED (already processed)

    -- Error info if processing failed
    error_message       VARCHAR(500),
    retry_count         INTEGER         NOT NULL DEFAULT 0,

    -- Signature was verified before storing
    signature_verified  BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Timestamps
    received_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE webhook_events
    ADD CONSTRAINT uq_webhook_events_razorpay_event_id UNIQUE (razorpay_event_id);

ALTER TABLE webhook_events
    ADD CONSTRAINT chk_webhook_events_status
    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'SKIPPED'));

CREATE INDEX IF NOT EXISTS idx_webhook_events_event_type
    ON webhook_events(event_type);

CREATE INDEX IF NOT EXISTS idx_webhook_events_status
    ON webhook_events(status);

CREATE INDEX IF NOT EXISTS idx_webhook_events_received_at
    ON webhook_events(received_at DESC);

-- ── Refunds ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refunds (

    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Links
    payment_id              UUID            NOT NULL REFERENCES payments(id),
    business_order_id       UUID            NOT NULL REFERENCES business_orders(id),

    -- Razorpay refund identifier
    razorpay_refund_id      VARCHAR(100),

    -- Amount in paise
    amount_paise            BIGINT          NOT NULL,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Refund status
    status                  VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING → PROCESSED | FAILED

    -- Reason (for audit)
    reason                  VARCHAR(100),
    notes                   TEXT,

    -- Idempotency key (prevents duplicate refund creation)
    idempotency_key         VARCHAR(100)    NOT NULL,

    -- Timestamps
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    processed_at            TIMESTAMPTZ
);

ALTER TABLE refunds
    ADD CONSTRAINT uq_refunds_razorpay_refund_id UNIQUE (razorpay_refund_id);

ALTER TABLE refunds
    ADD CONSTRAINT uq_refunds_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_status
    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'));

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_amount_positive
    CHECK (amount_paise > 0);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_id
    ON refunds(payment_id);

CREATE INDEX IF NOT EXISTS idx_refunds_business_order_id
    ON refunds(business_order_id);

-- ── Ledger Accounts ───────────────────────────────────────────────
-- Double-entry bookkeeping accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (

    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(20)     NOT NULL,   -- e.g., 'CASH', 'REVENUE', 'RAZORPAY_RECEIVABLE'
    name            VARCHAR(100)    NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,   -- ASSET, LIABILITY, REVENUE, EXPENSE
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE ledger_accounts
    ADD CONSTRAINT uq_ledger_accounts_code UNIQUE (code);

ALTER TABLE ledger_accounts
    ADD CONSTRAINT chk_ledger_accounts_type
    CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE', 'EQUITY'));

-- Seed the essential ledger accounts
INSERT INTO ledger_accounts (code, name, account_type, description)
VALUES
    ('RAZORPAY_RECEIVABLE', 'Razorpay Receivable',    'ASSET',   'Funds collected by Razorpay, pending settlement to merchant'),
    ('MERCHANT_BANK',        'Merchant Bank Account',  'ASSET',   'Merchant''s actual bank account after Razorpay settlement'),
    ('REVENUE',              'Payment Revenue',         'REVENUE', 'Revenue from successful payment captures'),
    ('REFUNDS_PAYABLE',      'Refunds Payable',         'LIABILITY','Refunds owed back to customers'),
    ('RAZORPAY_FEES',        'Razorpay Gateway Fees',  'EXPENSE', 'Fees charged by Razorpay per transaction')
ON CONFLICT (code) DO NOTHING;

-- ── Ledger Transactions ───────────────────────────────────────────
-- A ledger_transaction groups related ledger_entries (journal entries).
-- Each financial event (payment captured, refund issued) creates ONE transaction
-- with MULTIPLE entries that balance to zero (debits = credits).
CREATE TABLE IF NOT EXISTS ledger_transactions (

    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Source of this transaction
    source_type         VARCHAR(30)     NOT NULL,   -- 'PAYMENT', 'REFUND', 'SETTLEMENT', 'FEE'
    source_id           UUID,                       -- ID of the payment/refund/settlement

    -- Human description for audit
    description         VARCHAR(255)    NOT NULL,

    -- Idempotency: prevent double-posting the same event
    idempotency_key     VARCHAR(100)    NOT NULL,

    -- Total amount (for quick reference — not used for accounting math)
    amount_paise        BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Timestamps
    posted_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE ledger_transactions
    ADD CONSTRAINT uq_ledger_transactions_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE ledger_transactions
    ADD CONSTRAINT chk_ledger_transactions_source_type
    CHECK (source_type IN ('PAYMENT', 'REFUND', 'SETTLEMENT', 'FEE', 'ADJUSTMENT'));

-- ── Ledger Entries ────────────────────────────────────────────────
-- Individual debit/credit line items in a double-entry ledger.
-- For every ledger_transaction, SUM(debit) must equal SUM(credit).
CREATE TABLE IF NOT EXISTS ledger_entries (

    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id   UUID            NOT NULL REFERENCES ledger_transactions(id),
    ledger_account_id       UUID            NOT NULL REFERENCES ledger_accounts(id),

    -- Entry type
    entry_type              VARCHAR(6)      NOT NULL,   -- 'DEBIT' or 'CREDIT'
    amount_paise            BIGINT          NOT NULL,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',

    -- Description for this line
    description             VARCHAR(255),

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_type
    CHECK (entry_type IN ('DEBIT', 'CREDIT'));

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_amount_positive
    CHECK (amount_paise > 0);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction_id
    ON ledger_entries(ledger_transaction_id);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id
    ON ledger_entries(ledger_account_id);

-- ── Settlements ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS settlements (

    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Razorpay settlement identifier
    razorpay_settlement_id  VARCHAR(100)    NOT NULL,

    -- Settlement details
    amount_paise            BIGINT          NOT NULL,
    fees_paise              BIGINT          NOT NULL DEFAULT 0,
    tax_paise               BIGINT          NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',

    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- UTR: Unique Transaction Reference (bank transfer reference)
    utr                     VARCHAR(100),

    -- Settlement date
    settled_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE settlements
    ADD CONSTRAINT uq_settlements_razorpay_id UNIQUE (razorpay_settlement_id);

ALTER TABLE settlements
    ADD CONSTRAINT chk_settlements_status
    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'));

-- ── Audit Log ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_logs (

    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who did the action
    actor_user_id   UUID            REFERENCES users(id) ON DELETE SET NULL,
    actor_email     VARCHAR(255),   -- Denormalized: user may be deleted later

    -- What happened
    action          VARCHAR(100)    NOT NULL,   -- e.g., 'ORDER_CREATED', 'REFUND_INITIATED'
    entity_type     VARCHAR(50),               -- e.g., 'BusinessOrder', 'Payment'
    entity_id       UUID,

    -- Details
    description     VARCHAR(500),
    metadata        TEXT,           -- JSON blob of extra context

    -- Request context
    ip_address      VARCHAR(45),    -- IPv4 or IPv6
    user_agent      VARCHAR(500),

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_user_id
    ON audit_logs(actor_user_id);

CREATE INDEX IF NOT EXISTS idx_audit_logs_entity
    ON audit_logs(entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at
    ON audit_logs(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_action
    ON audit_logs(action);
