package com.ecommerce.order.infrastructure.kafka;

import com.ecommerce.order.application.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class OrderKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(OrderKafkaListener.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public OrderKafkaListener(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${order.kafka.topics.payment-events:payment-events}",
                   groupId = "order-service")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        String payload = record.value();
        log.info("Received event {} on topic {}", eventType, record.topic());

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventId = root.has("eventId") ? root.get("eventId").asText() : record.key();
            String orderId = root.has("orderId") ? root.get("orderId").asText() : record.key();

            switch (eventType) {
                case "PaymentCompleted" -> {
                    orderService.confirmOrder(eventId, orderId);
                    log.info("Handled PaymentCompleted for order: {}", orderId);
                }
                case "PaymentFailed" -> {
                    String reason = root.has("reason") ? root.get("reason").asText() : "PAYMENT_FAILED";
                    orderService.cancelOrder(eventId, orderId, reason);
                    log.info("Handled PaymentFailed for order: {} (reason: {})", orderId, reason);
                }
                default -> log.debug("Ignoring unrecognized payment event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process payment event {}: {}", eventType, e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "${order.kafka.topics.inventory-events:inventory-events}",
                   groupId = "order-service")
    public void onInventoryEvent(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        String payload = record.value();
        log.info("Received event {} on topic {}", eventType, record.topic());

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventId = root.has("eventId") ? root.get("eventId").asText() : record.key();
            String orderId = root.has("orderId") ? root.get("orderId").asText() : record.key();

            switch (eventType) {
                case "InventoryReservationFailed" -> {
                    String reason = root.has("reason") ? root.get("reason").asText() : "INVENTORY_RESERVATION_FAILED";
                    orderService.cancelOrder(eventId, orderId, reason);
                    log.info("Handled InventoryReservationFailed for order: {}", orderId);
                }
                default -> log.debug("Ignoring non-failure inventory event in order-service: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process inventory event {}: {}", eventType, e.getMessage(), e);
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
