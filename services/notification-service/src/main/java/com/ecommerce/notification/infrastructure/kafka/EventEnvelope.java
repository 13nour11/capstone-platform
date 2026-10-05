package com.ecommerce.notification.infrastructure.kafka;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON envelope of every Saga event (frozen in ADD §3). Kafka key = orderId. Unknown fields are ignored so
 * producers can add fields without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateId,
        String traceparent,
        JsonNode payload) {
}
