-- Bonus B3: every product belongs to one tenant. Existing rows (the demo catalogue) belong to tenant-a.
ALTER TABLE products ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant-a';
CREATE INDEX idx_products_tenant_id ON products (tenant_id, id);

-- A small catalogue for the second shop, so isolation is visible in the demo.
INSERT INTO products (name, description, price, category_id, tenant_id) VALUES
    ('Gaming Headset', 'Tenant B: 7.1 surround',       59.00, 1, 'tenant-b'),
    ('Refactoring',    'Tenant B: Martin Fowler',      44.00, 2, 'tenant-b'),
    ('Standing Desk',  'Tenant B: electric, 140 cm',  399.00, 3, 'tenant-b');

-- Bonus B1: read model fed by ReviewSubmitted. One row per review: a redelivered event hits the primary key and is
-- ignored, so the average can never drift (no incremental arithmetic).
CREATE TABLE product_review_ratings (
    review_id  VARCHAR(64) PRIMARY KEY,
    product_id BIGINT      NOT NULL,
    rating     INTEGER     NOT NULL CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_product_review_ratings_product_id ON product_review_ratings (product_id);
