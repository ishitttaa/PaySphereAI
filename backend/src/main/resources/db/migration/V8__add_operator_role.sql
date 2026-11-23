INSERT INTO roles (name, description)
VALUES ('PLATFORM_OPERATOR', 'Read-only access to payments, orders, ledger and reports')
ON CONFLICT (name) DO NOTHING;