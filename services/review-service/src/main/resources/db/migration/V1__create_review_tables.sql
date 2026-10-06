-- Bonus B1. One review per (product, customer): the unique constraint is what rejects a second review (409).
CREATE TABLE reviews (
    id          UUID          PRIMARY KEY,
    product_id  BIGINT        NOT NULL,
    customer_id VARCHAR(64)   NOT NULL,
    rating      INTEGER       NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_reviews_product_customer UNIQUE (product_id, customer_id)
);

CREATE INDEX idx_reviews_product_created ON reviews (product_id, created_at DESC);

-- Transactional outbox (same design as payment-service): ReviewSubmitted is written with the review.
CREATE TABLE outbox_event (
    id           UUID         PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    traceparent  VARCHAR(55),
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   VARCHAR(500)
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at) WHERE published_at IS NULL;
