package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.domain.event.OrderCancelled;
import com.ecommerce.inventory.domain.event.OrderConfirmed;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.event.PaymentCompleted;
import com.ecommerce.inventory.domain.event.PaymentFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Inventory's Saga consumers. A failure is not swallowed: it propagates to the container's error handler,
 * which retries and then parks the record on {@code <topic>.DLT} (NFR-10). Unreadable payloads go there at once.
 */
@Component
public class InventoryKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryKafkaListener.class);

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public InventoryKafkaListener(InventoryService inventoryService, ObjectMapper objectMapper) {
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${inventory.kafka.topics.order-events:order-events}",
                   groupId = "inventory-service")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        String payload = record.value();
        log.info("Received event {} on topic {}", eventType, record.topic());

        switch (eventType) {
            case "OrderPlaced" -> inventoryService.processOrderPlaced(read(payload, OrderPlaced.class));
            case "OrderCancelled" -> {
                OrderCancelled event = read(payload, OrderCancelled.class);
                inventoryService.releaseReservation(event.eventId(), event.orderId(), event.reason());
            }
            case "OrderConfirmed" -> {
                OrderConfirmed event = read(payload, OrderConfirmed.class);
                inventoryService.confirmReservation(event.eventId(), event.orderId());
            }
            default -> log.debug("Ignoring unrecognized order event type: {}", eventType);
        }
    }

    @KafkaListener(topics = "${inventory.kafka.topics.payment-events:payment-events}",
                   groupId = "inventory-service")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        String payload = record.value();
        log.info("Received event {} on topic {}", eventType, record.topic());

        switch (eventType) {
            case "PaymentFailed" -> {
                PaymentFailed event = read(payload, PaymentFailed.class);
                inventoryService.releaseReservation(event.eventId(), event.orderId(), event.reason());
            }
            case "PaymentCompleted" -> {
                PaymentCompleted event = read(payload, PaymentCompleted.class);
                inventoryService.confirmReservation(event.eventId(), event.orderId());
            }
            default -> log.debug("Ignoring unrecognized payment event type: {}", eventType);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Unreadable " + type.getSimpleName() + " payload", e);
        }
    }

    private String extractEventType(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        try {
            JsonNode root = objectMapper.readTree(record.value());
            if (root.has("eventType")) {
                return root.get("eventType").asText();
            }
        } catch (JsonProcessingException ignored) {
            // No eventType header and no JSON body: nothing this consumer handles
        }
        return "UNKNOWN";
    }
}
