-- One payment per order: the unique constraint is what makes charging exactly-once (FR-08).
CREATE TABLE payments (
    id             UUID           PRIMARY KEY,
    order_id       VARCHAR(64)    NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status         VARCHAR(16)    NOT NULL,
    failure_reason VARCHAR(255),
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_payments_order_id UNIQUE (order_id)
);

-- Stored result per Idempotency-Key, replayed on a retried request.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- Events already handled by a consumer (NFR-10: at-least-once delivery, idempotent consumers).
CREATE TABLE processed_event (
    event_id     VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

-- Transactional outbox: written with the business change, published by the polling publisher.
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
