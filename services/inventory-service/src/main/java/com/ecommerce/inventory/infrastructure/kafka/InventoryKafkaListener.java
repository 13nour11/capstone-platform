package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.domain.event.OrderCancelled;
import com.ecommerce.inventory.domain.event.OrderConfirmed;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.event.PaymentCompleted;
import com.ecommerce.inventory.domain.event.PaymentFailed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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

        try {
            switch (eventType) {
                case "OrderPlaced" -> {
                    OrderPlaced event = objectMapper.readValue(payload, OrderPlaced.class);
                    inventoryService.processOrderPlaced(event);
                }
                case "OrderCancelled" -> {
                    OrderCancelled event = objectMapper.readValue(payload, OrderCancelled.class);
                    inventoryService.releaseReservation(event.eventId(), event.orderId(), event.reason());
                }
                case "OrderConfirmed" -> {
                    OrderConfirmed event = objectMapper.readValue(payload, OrderConfirmed.class);
                    inventoryService.confirmReservation(event.eventId(), event.orderId());
                }
                default -> log.debug("Ignoring unrecognized order event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process order event of type {}: {}", eventType, e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "${inventory.kafka.topics.payment-events:payment-events}",
                   groupId = "inventory-service")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        String payload = record.value();
        log.info("Received event {} on topic {}", eventType, record.topic());

        try {
            switch (eventType) {
                case "PaymentFailed" -> {
                    PaymentFailed event = objectMapper.readValue(payload, PaymentFailed.class);
                    inventoryService.releaseReservation(event.eventId(), event.orderId(), event.reason());
                }
                case "PaymentCompleted" -> {
                    PaymentCompleted event = objectMapper.readValue(payload, PaymentCompleted.class);
                    inventoryService.confirmReservation(event.eventId(), event.orderId());
                }
                default -> log.debug("Ignoring unrecognized payment event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process payment event of type {}: {}", eventType, e.getMessage(), e);
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
        } catch (Exception ignored) {
        }
        return "UNKNOWN";
    }
}
