-- N9 step 2: every product and order belongs to exactly one merchant.
-- Existing rows go to a Default Merchant so the NOT NULL rule can be applied.
INSERT INTO merchants (name) VALUES ('Default Merchant')
ON CONFLICT (name) DO NOTHING;

ALTER TABLE products        ADD COLUMN IF NOT EXISTS merchant_id UUID REFERENCES merchants(id);
ALTER TABLE business_orders ADD COLUMN IF NOT EXISTS merchant_id UUID REFERENCES merchants(id);

UPDATE products SET merchant_id = (SELECT id FROM merchants WHERE name = 'Default Merchant')
WHERE merchant_id IS NULL;
UPDATE business_orders SET merchant_id = (SELECT id FROM merchants WHERE name = 'Default Merchant')
WHERE merchant_id IS NULL;

ALTER TABLE products        ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE business_orders ALTER COLUMN merchant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_products_merchant_id        ON products(merchant_id);
CREATE INDEX IF NOT EXISTS idx_business_orders_merchant_id ON business_orders(merchant_id);