package com.ecommerce.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.apache.kafka.clients.producer.ProducerRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
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
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private NotificationSender sender;

    @Test
    void shouldNotifyCustomer_whenOrderIsConfirmed() {
        String orderId = newOrderId();

        publish(orderId, event(UUID.randomUUID().toString(), "OrderConfirmed", orderId));

        verify(sender, timeout(WAIT.toMillis())).send(argThat(n -> n.orderId().equals(orderId)
                && n.type() == OrderNotification.Type.ORDER_CONFIRMED));
    }

    @Test
    void shouldNotifyCustomer_whenOrderServicePublishesTheFrozenContract() {
        String orderId = newOrderId();
        String body = """
                {"eventId":"%s","orderId":"%s","customerId":"cust-9","reason":"PAYMENT_FAILED","occurredAt":"2026-10-01T10:00:00Z"}"""
                .formatted(UUID.randomUUID(), orderId);
        ProducerRecord<String, String> record = new ProducerRecord<>("order-events", orderId, body);
        record.headers().add("eventType", "OrderCancelled".getBytes(StandardCharsets.UTF_8));

        kafka.send(record).join();

        verify(sender, timeout(WAIT.toMillis())).send(argThat(n -> n.orderId().equals(orderId)
                && n.type() == OrderNotification.Type.ORDER_CANCELLED && "cust-9".equals(n.customerId())));
    }

    @Test
    void shouldNotifyOnce_whenSameEventIsRedelivered() {
        String orderId = newOrderId();
        String eventId = UUID.randomUUID().toString();

        publish(orderId, event(eventId, "OrderConfirmed", orderId));
        publish(orderId, event(eventId, "OrderConfirmed", orderId));
        // Same key, same partition: once this later event is handled, both copies above were consumed
        publish(orderId, event(UUID.randomUUID().toString(), "OrderCancelled", orderId));

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

        publish(orderId, event(UUID.randomUUID().toString(), "OrderConfirmed", orderId));

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltCount()).isEqualTo(parkedBefore + 1));
        verify(sender, times(4)).send(argThat(n -> n.orderId().equals(orderId)));
    }

    @Test
    void shouldParkPoisonMessageWithoutRetry_whenJsonIsMalformed() {
        String orderId = newOrderId();
        double parkedBefore = dltCount();

        publish(orderId, "{not json");

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltCount()).isEqualTo(parkedBefore + 1));
        verify(sender, after(500).never()).send(argThat(n -> n.orderId().equals(orderId)));
    }

    private double dltCount() {
        return meterRegistry.counter("notifications.dlt", "topic", "order-events.DLT").count();
    }

    private void publish(String key, String value) {
        kafka.send("order-events", key, value).join();
    }

    private static String newOrderId() {
        return "ord-" + UUID.randomUUID();
    }

    private static String event(String eventId, String eventType, String orderId) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-10-01T10:00:00Z",
                 "aggregateId":"%s","payload":{"orderId":"%s","customerId":"cust-1"}}"""
                .formatted(eventId, eventType, orderId, orderId);
    }
}
