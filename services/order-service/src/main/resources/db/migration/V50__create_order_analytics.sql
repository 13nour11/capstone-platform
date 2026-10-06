-- Bonus B2 read model (Member C range V50+). One row per order, built from order-events.
CREATE TABLE analytics_order (
    order_id     VARCHAR(64)    PRIMARY KEY,
    status       VARCHAR(16)    NOT NULL,
    total_amount NUMERIC(12, 2),
    placed_at    TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_analytics_order_placed_at ON analytics_order (placed_at);

-- Events already applied to the projection, so a redelivered event is never counted twice.
CREATE TABLE analytics_processed_event (
    event_id     VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
