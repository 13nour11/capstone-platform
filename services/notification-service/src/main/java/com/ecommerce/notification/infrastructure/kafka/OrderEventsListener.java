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
    public void onOrderEvent(String message,
                             @Header(name = "eventType", required = false) String eventTypeHeader) {
        toNotification(message, eventTypeHeader).ifPresent(notifications::notifyCustomer);
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
        return toNotification(message, null);
    }

    /**
     * Accepts both shapes the platform has used: the flat event the producers actually publish
     * ({@code {eventId, orderId, customerId, ...}} with the type in the {@code eventType} Kafka
     * header, ADD §3.2), and the wrapped {@link EventEnvelope} with a nested {@code payload}.
     */
    Optional<OrderNotification> toNotification(String message, String eventTypeHeader) {
        JsonNode root = parse(message);
        String eventId = text(root, "eventId")
                .orElseThrow(() -> new InvalidEventException("eventId is required"));
        String eventType = Optional.ofNullable(eventTypeHeader)
                .or(() -> text(root, "eventType"))
                .orElseThrow(() -> new InvalidEventException(
                        "eventType is required, in the Kafka header or the body, for event " + eventId));

        Type type = NOTIFIED_EVENTS.get(eventType);
        if (type == null) {
            log.debug("Skipping eventType={} eventId={}", eventType, eventId);
            return Optional.empty();
        }

        // Wrapped envelope: the business fields sit under "payload". Flat event: they are at the top.
        JsonNode fields = root.hasNonNull("payload") ? root.get("payload") : root;
        String orderId = text(fields, "orderId").orElse(text(root, "aggregateId").orElse(null));
        if (orderId == null) {
            throw new InvalidEventException("orderId missing in event " + eventId);
        }
        String customerId = text(fields, "customerId")
                .orElseThrow(() -> new InvalidEventException("customerId missing in event " + eventId));

        return Optional.of(new OrderNotification(eventId, type, orderId, customerId,
                text(fields, "reason").orElse(null)));
    }

    private JsonNode parse(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                throw new InvalidEventException("Event body must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed event JSON", e);
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        return Optional.ofNullable(node)
                .map(n -> n.get(field))
                .filter(value -> !value.isNull())
                .map(JsonNode::asText);
    }
}
