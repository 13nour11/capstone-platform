-- A reservation row per (order, product). The previous single-row-per-order shape stored the first
-- item's product_id with the summed quantity, so a multi-item order released the wrong stock.
ALTER TABLE reservation DROP CONSTRAINT reservation_pkey;
ALTER TABLE reservation ADD CONSTRAINT reservation_pkey PRIMARY KEY (order_id, product_id);

-- Orders the saga has ended. The sweeper needs this to tell a dead saga from one still in flight:
-- releasing on age alone returned stock for orders that were still awaiting payment (oversell).
CREATE TABLE IF NOT EXISTS cancelled_order (
    order_id VARCHAR(64) PRIMARY KEY,
    cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
