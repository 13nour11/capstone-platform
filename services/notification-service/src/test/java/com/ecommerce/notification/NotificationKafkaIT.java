package com.ecommerce.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.ecommerce.notification.application.AlertBroadcaster;
import com.ecommerce.notification.application.NotificationSender;
import com.ecommerce.notification.domain.OrderNotification;

import io.micrometer.core.instrument.MeterRegistry;

/** FR-11 / NFR-10 on a real broker: delivery, duplicate redelivery, retry then DLT, poison message. */
@SpringBootTest
@Testcontainers
class NotificationKafkaIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic orderEvents() {
            return TopicBuilder.name("order-events").partitions(3).build();
        }

        @Bean
        NewTopic inventoryAlerts() {
            return TopicBuilder.name("inventory-alerts").partitions(3).build();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private NotificationSender sender;

    @MockitoSpyBean
    private AlertBroadcaster alerts;

    @Test
    void shouldNotifyCustomer_whenOrderIsConfirmed() {
        String orderId = newOrderId();

        publish(orderId, "OrderConfirmed", event(UUID.randomUUID().toString(), orderId));

        verify(sender, timeout(WAIT.toMillis())).send(argThat(n -> n.orderId().equals(orderId)
                && n.type() == OrderNotification.Type.ORDER_CONFIRMED));
    }

    @Test
    void shouldNotifyOnce_whenSameEventIsRedelivered() {
        String orderId = newOrderId();
        String eventId = UUID.randomUUID().toString();

        publish(orderId, "OrderConfirmed", event(eventId, orderId));
        publish(orderId, "OrderConfirmed", event(eventId, orderId));
        // Same key, same partition: once this later event is handled, both copies above were consumed
        publish(orderId, "OrderCancelled", event(UUID.randomUUID().toString(), orderId));

        verify(sender, timeout(WAIT.toMillis())).send(argThat(n -> n.orderId().equals(orderId)
                && n.type() == OrderNotification.Type.ORDER_CANCELLED));
        verify(sender, times(1)).send(argThat(n -> n.eventId().equals(eventId)));
    }

    @Test
    void shouldRetryThenParkInDlt_whenSendKeepsFailing() {
        String orderId = newOrderId();
        willThrow(new IllegalStateException("smtp down"))
                .given(sender).send(argThat(n -> n != null && n.orderId().equals(orderId)));
        double parkedBefore = dltCount();

        publish(orderId, "OrderConfirmed", event(UUID.randomUUID().toString(), orderId));

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltCount()).isEqualTo(parkedBefore + 1));
        verify(sender, times(4)).send(argThat(n -> n.orderId().equals(orderId)));
    }

    @Test
    void shouldParkPoisonMessageWithoutRetry_whenJsonIsMalformed() {
        String orderId = newOrderId();
        double parkedBefore = dltCount();

        publish(orderId, "OrderConfirmed", "{not json");

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltCount()).isEqualTo(parkedBefore + 1));
        verify(sender, after(500).never()).send(argThat(n -> n.orderId().equals(orderId)));
    }

    private double dltCount() {
        return meterRegistry.counter("notifications.dlt", "topic", "order-events.DLT").count();
    }

    /** As the order-service outbox publishes: flat JSON, event type in the record header. */
    private void publish(String key, String eventType, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>("order-events", key, value);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private void publishAlert(String productId, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>("inventory-alerts", productId, value);
        record.headers().add("eventType", "LowStock".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private static String newOrderId() {
        return "ord-" + UUID.randomUUID();
    }

    private static String event(String eventId, String orderId) {
        return """
                {"eventId":"%s","orderId":"%s","customerId":"cust-1","occurredAt":"2026-10-01T10:00:00Z"}"""
                .formatted(eventId, orderId);
    }

    private static String lowStock(String eventId, long productId) {
        return """
                {"eventId":"%s","productId":%d,"available":2,"threshold":5,"occurredAt":"2026-10-01T10:00:00Z"}"""
                .formatted(eventId, productId);
    }

    // --- Bonus B4: a LowStock event reaches the SSE broadcaster within 2 seconds ---

    @Test
    void shouldReachAlertBroadcasterWithinTwoSeconds_whenLowStockIsPublished() {
        // Warm-up: the alerts consumer group must own its partitions before timing starts
        String warmUp = UUID.randomUUID().toString();
        publishAlert("1", lowStock(warmUp, 1));
        verify(alerts, timeout(WAIT.toMillis())).broadcast(argThat(a -> a.eventId().equals(warmUp)));

        String eventId = UUID.randomUUID().toString();
        publishAlert("4", lowStock(eventId, 4));
        publishAlert("4", lowStock(eventId, 4));

        verify(alerts, timeout(2_000).times(2)).broadcast(argThat(a -> a.eventId().equals(eventId)));
        // The redelivered copy reaches broadcast() too; AlertBroadcasterTest shows it is pushed only once
    }
}
