package com.ecommerce.order.analytics.infrastructure;

import com.ecommerce.order.analytics.application.OrderAnalyticsProjector;
import com.ecommerce.order.analytics.domain.AnalyticsOrderStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Feeds the analytics projection from {@code order-events} in its own consumer group, so it
 * never competes with order-service's Saga consumers. Events are routed by the {@code eventType}
 * header written by the outbox publisher.
 */
@Component
public class OrderEventsAnalyticsListener {

    static final String TOPIC = "order-events";
    static final String GROUP_ID = "order-service-analytics";
    static final String EVENT_TYPE_HEADER = "eventType";

    private final OrderAnalyticsProjector projector;
    private final ObjectReader jsonReader;

    public OrderEventsAnalyticsListener(OrderAnalyticsProjector projector, ObjectMapper objectMapper) {
        this.projector = projector;
        // Amounts must stay exact: never let a JSON number pass through double.
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID, containerFactory = AnalyticsKafkaConfig.CONTAINER_FACTORY)
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, EVENT_TYPE_HEADER);
        if (eventType == null) {
            return;
        }
        switch (eventType) {
            case "OrderPlaced" -> {
                JsonNode event = parse(record);
                projector.onOrderPlaced(text(event, "eventId"), text(event, "orderId"),
                        amount(event), occurredAt(event, record));
            }
            case "OrderConfirmed" -> {
                JsonNode event = parse(record);
                projector.onOrderClosed(text(event, "eventId"), text(event, "orderId"), AnalyticsOrderStatus.CONFIRMED);
            }
            case "OrderCancelled" -> {
                JsonNode event = parse(record);
                projector.onOrderClosed(text(event, "eventId"), text(event, "orderId"), AnalyticsOrderStatus.CANCELLED);
            }
            default -> {
                // Other order events do not change the analytics.
            }
        }
    }

    private JsonNode parse(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = jsonReader.readTree(record.value());
            if (node == null || !node.isObject()) {
                throw new InvalidOrderEventException("Order event is not a JSON object: " + record.value());
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidOrderEventException("Unreadable order event", e);
        }
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new InvalidOrderEventException("Order event is missing '" + field + "': " + event);
        }
        return value.asText();
    }

    private static BigDecimal amount(JsonNode event) {
        JsonNode value = event.get("totalAmount");
        if (value == null || !value.isNumber()) {
            throw new InvalidOrderEventException("OrderPlaced is missing a numeric 'totalAmount': " + event);
        }
        return value.decimalValue();
    }

    /** The event's own timestamp; the broker timestamp is the fallback when the producer omits it. */
    private static Instant occurredAt(JsonNode event, ConsumerRecord<String, String> record) {
        JsonNode value = event.get("occurredAt");
        if (value == null || value.isNull()) {
            return Instant.ofEpochMilli(record.timestamp());
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            throw new InvalidOrderEventException("OrderPlaced has an invalid 'occurredAt': " + value, e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
