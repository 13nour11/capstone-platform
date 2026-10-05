package com.ecommerce.review.infrastructure.outbox;

/** Kafka record headers every outbox event carries; consumers route on {@link #EVENT_TYPE}. */
public final class OutboxHeaders {

    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_ID = "eventId";

    private OutboxHeaders() {
    }
}
