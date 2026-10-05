-- FR-07: an order with several products reserves each product separately (one row per order line).
-- Previously the order id was the key, so only the first product of a multi-item order could be released.
ALTER TABLE reservation DROP CONSTRAINT reservation_pkey;
ALTER TABLE reservation ADD COLUMN id BIGSERIAL PRIMARY KEY;
ALTER TABLE reservation ADD CONSTRAINT uk_reservation_order_product UNIQUE (order_id, product_id);
CREATE INDEX idx_reservation_order_id ON reservation (order_id);

-- NFR-05: orders this service knows are cancelled (from PaymentFailed / OrderCancelled). The sweeper releases any
-- reservation still RESERVED for such an order, and a late OrderPlaced for it never reserves stock.
CREATE TABLE cancelled_order (
    order_id     VARCHAR(64)              PRIMARY KEY,
    cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Bonus B4: one LowStock alert per drop below the threshold; the flag resets when stock is back above it.
ALTER TABLE stock ADD COLUMN low_stock_alerted BOOLEAN NOT NULL DEFAULT FALSE;
