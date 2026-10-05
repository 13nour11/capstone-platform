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
 * Consumes order-events. A failed send is retried on order-events.retry-N topics with exponential backoff
 * (4 attempts in total by default); after that, or at once for a poison message, it is parked in
 * order-events.DLT, where an alert is logged and counted (FR-11).
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order-events";

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
    public void onOrderEvent(String message) {
        toNotification(message).ifPresent(notifications::notifyCustomer);
    }

    @DltHandler
    public void onDeadLetter(String message,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                             @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        log.error("ALERT notification parked in {} key={} error={}", topic, key, error);
        meterRegistry.counter("notifications.dlt", "topic", topic).increment();
    }

    Optional<OrderNotification> toNotification(String message) {
        EventEnvelope event = parse(message);
        Type type = NOTIFIED_EVENTS.get(event.eventType());
        if (type == null) {
            log.debug("Skipping eventType={} eventId={}", event.eventType(), event.eventId());
            return Optional.empty();
        }
        JsonNode payload = event.payload();
        String orderId = text(payload, "orderId").orElse(event.aggregateId());
        String customerId = text(payload, "customerId")
                .orElseThrow(() -> new InvalidEventException("customerId missing in event " + event.eventId()));
        if (orderId == null) {
            throw new InvalidEventException("orderId missing in event " + event.eventId());
        }
        return Optional.of(new OrderNotification(event.eventId(), type, orderId, customerId,
                text(payload, "reason").orElse(null)));
    }

    private EventEnvelope parse(String message) {
        try {
            EventEnvelope event = objectMapper.readValue(message, EventEnvelope.class);
            if (event == null || event.eventId() == null || event.eventType() == null) {
                throw new InvalidEventException("eventId and eventType are required");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed event JSON", e);
        }
    }

    private static Optional<String> text(JsonNode payload, String field) {
        return Optional.ofNullable(payload)
                .map(node -> node.get(field))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText);
    }
}
