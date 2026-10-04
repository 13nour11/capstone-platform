CREATE TABLE IF NOT EXISTS stock (
    product_id BIGINT PRIMARY KEY,
    available INT NOT NULL CHECK (available >= 0),
    reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reservation (
    order_id VARCHAR(64) PRIMARY KEY,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reservation_status_created ON reservation(status, created_at);

-- Initial seed data for test products
INSERT INTO stock (product_id, available, reserved, version) VALUES
(1, 100, 0, 0),
(2, 50, 0, 0),
(3, 10, 0, 0),
(4, 0, 0, 0)
ON CONFLICT (product_id) DO NOTHING;
