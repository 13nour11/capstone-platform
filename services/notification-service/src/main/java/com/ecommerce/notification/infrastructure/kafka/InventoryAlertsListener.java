package com.ecommerce.notification.infrastructure.kafka;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.ecommerce.notification.application.AlertBroadcaster;
import com.ecommerce.notification.domain.LowStockAlert;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bonus B4: consumes {@code inventory-alerts} and pushes each LowStock alert to the connected admins.
 * Its own consumer group, so it never competes with the order-events consumer.
 */
@Component
public class InventoryAlertsListener {

    static final String TOPIC = "inventory-alerts";
    static final String LOW_STOCK = "LowStock";

    private static final Logger log = LoggerFactory.getLogger(InventoryAlertsListener.class);

    private final AlertBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public InventoryAlertsListener(AlertBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC, groupId = "notification-service-alerts")
    public void onAlert(String message,
                        @Header(name = OrderEventsListener.EVENT_TYPE_HEADER, required = false) String eventType) {
        if (!LOW_STOCK.equals(eventType)) {
            log.debug("Skipping alert eventType={}", eventType);
            return;
        }
        broadcaster.broadcast(toAlert(message));
    }

    LowStockAlert toAlert(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            if (event == null || !event.hasNonNull("eventId") || !event.hasNonNull("productId")) {
                throw new InvalidEventException("LowStock needs eventId and productId");
            }
            Instant occurredAt = event.hasNonNull("occurredAt") ? Instant.parse(event.get("occurredAt").asText()) : null;
            return new LowStockAlert(event.get("eventId").asText(), event.get("productId").asLong(),
                    event.path("available").asInt(), event.path("threshold").asInt(), occurredAt);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed LowStock JSON", e);
        }
    }
}
