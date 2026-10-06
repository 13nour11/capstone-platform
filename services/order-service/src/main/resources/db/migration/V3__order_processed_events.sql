CREATE TABLE IF NOT EXISTS processed_event (
    event_id VARCHAR(64) NOT NULL,
    consumer VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer)
);
