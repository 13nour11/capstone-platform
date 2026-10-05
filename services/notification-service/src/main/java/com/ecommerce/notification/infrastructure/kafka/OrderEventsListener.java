package com.ecommerce.notification.infrastructure.kafka;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import com.ecommerce.notification.application.SendOrderNotificationService;
import com.ecommerce.notification.domain.OrderNotification;
import com.ecommerce.notification.domain.OrderNotification.Type;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Consumes order-events. Every Saga event is a flat JSON object routed by the {@code eventType} record header
 * (the contract the order, inventory and payment outboxes publish, ADD §3).
 * A failed send is retried on order-events.retry-N topics with exponential backoff
 * (4 attempts in total by default); after that, or at once for a poison message, it is parked in
 * order-events.DLT, where an alert is logged and counted (FR-11).
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order-events";
    static final String EVENT_TYPE_HEADER = "eventType";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private static final Map<String, Type> NOTIFIED_EVENTS = Map.of(
            "OrderConfirmed", Type.ORDER_CONFIRMED,
            "OrderCancelled", Type.ORDER_CANCELLED);

    private final SendOrderNotificationService notifications;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OrderEventsListener(SendOrderNotificationService notifications, ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(
            attempts = "${notification.retry.attempts:4}",
            backoff = @Backoff(delayExpression = "${notification.retry.delay-ms:1000}",
                    multiplierExpression = "${notification.retry.multiplier:2.0}"),
            retryTopicSuffix = ".retry",
            dltTopicSuffix = ".DLT",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = InvalidEventException.class)
    @KafkaListener(topics = TOPIC, groupId = "${spring.kafka.consumer.group-id:notification-service}")
    public void onOrderEvent(String message, @Header(name = EVENT_TYPE_HEADER, required = false) String eventType) {
        toNotification(eventType, message).ifPresent(notifications::notifyCustomer);
    }

    @DltHandler
    public void onDeadLetter(String message,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                             @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        log.error("ALERT notification parked in {} key={} error={}", topic, key, error);
        meterRegistry.counter("notifications.dlt", "topic", topic).increment();
    }

    Optional<OrderNotification> toNotification(String eventType, String message) {
        Type type = eventType == null ? null : NOTIFIED_EVENTS.get(eventType);
        if (type == null) {
            log.debug("Skipping eventType={}", eventType);
            return Optional.empty();
        }
        JsonNode event = parse(message);
        String eventId = text(event, "eventId")
                .orElseThrow(() -> new InvalidEventException("eventId missing in " + eventType));
        String orderId = text(event, "orderId")
                .orElseThrow(() -> new InvalidEventException("orderId missing in event " + eventId));
        String customerId = text(event, "customerId")
                .orElseThrow(() -> new InvalidEventException("customerId missing in event " + eventId));
        return Optional.of(new OrderNotification(eventId, type, orderId, customerId,
                text(event, "reason").orElse(null)));
    }

    private JsonNode parse(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            if (event == null || !event.isObject()) {
                throw new InvalidEventException("Event is not a JSON object");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed event JSON", e);
        }
    }

    private static Optional<String> text(JsonNode event, String field) {
        return Optional.ofNullable(event.get(field))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank());
    }
}
