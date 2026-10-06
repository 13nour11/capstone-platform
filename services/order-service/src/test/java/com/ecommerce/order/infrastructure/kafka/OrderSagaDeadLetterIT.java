package com.ecommerce.order.infrastructure.kafka;

import com.ecommerce.order.application.OrderService;
import com.ecommerce.order.domain.exception.IllegalOrderStateException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * NFR-10 over a real broker: the order Saga consumers retry a failing record, then park it on
 * {@code <topic>.DLT}; a record that can never succeed goes there without retries.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {"payment-events", "payment-events.DLT", "inventory-events", "inventory-events.DLT", "order-events"})
class OrderSagaDeadLetterIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ATTEMPTS = 1 + (int) KafkaErrorHandlingConfig.MAX_RETRIES;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("A transient failure is retried and the Saga step succeeds; nothing reaches the DLT")
    void shouldRetryTransientFailure_thenSucceed() {
        String orderId = "ord-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("database briefly unavailable");
            }
            return null;
        }).when(orderService).confirmOrder(anyString(), eq(orderId));

        send("payment-events", orderId, "PaymentCompleted", payload(orderId));

        verify(orderService, timeout(TIMEOUT.toMillis()).times(3)).confirmOrder(anyString(), eq(orderId));
        assertThat(findOn("payment-events.DLT", orderId, Duration.ofSeconds(3))).isNull();
    }

    @Test
    @DisplayName("A failure that never clears is retried, then parked on payment-events.DLT")
    void shouldParkOnDlt_whenRetriesAreExhausted() {
        String orderId = "ord-" + UUID.randomUUID();
        doThrow(new IllegalStateException("database down"))
                .when(orderService).cancelOrder(anyString(), eq(orderId), anyString());

        send("payment-events", orderId, "PaymentFailed", payload(orderId));

        ConsumerRecord<String, String> parked = findOn("payment-events.DLT", orderId, TIMEOUT);
        assertThat(parked).isNotNull();
        assertThat(header(parked, "eventType")).isEqualTo("PaymentFailed");
        verify(orderService, times(ATTEMPTS)).cancelOrder(anyString(), eq(orderId), anyString());
    }

    @Test
    @DisplayName("PaymentCompleted for an already CANCELLED order goes straight to the DLT, without retries")
    void shouldParkConflictImmediately() {
        String orderId = "ord-" + UUID.randomUUID();
        doThrow(new IllegalOrderStateException("Cannot transition order from CANCELLED to CONFIRMED"))
                .when(orderService).confirmOrder(anyString(), eq(orderId));

        send("payment-events", orderId, "PaymentCompleted", payload(orderId));

        assertThat(findOn("payment-events.DLT", orderId, TIMEOUT)).isNotNull();
        verify(orderService, after(1_500).times(1)).confirmOrder(anyString(), eq(orderId));
    }

    @Test
    @DisplayName("An unreadable inventory event is parked on inventory-events.DLT and never reaches the service")
    void shouldParkUnreadableEvent_withoutCallingTheService() {
        String orderId = "ord-" + UUID.randomUUID();

        send("inventory-events", orderId, "InventoryReservationFailed", "{not json");

        assertThat(findOn("inventory-events.DLT", orderId, TIMEOUT)).isNotNull();
        verify(orderService, never()).cancelOrder(anyString(), eq(orderId), anyString());
    }

    private static String payload(String orderId) {
        return "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"reason\":\"CARD_DECLINED\",\"occurredAt\":\"%s\"}"
                .formatted(UUID.randomUUID(), orderId, Instant.now());
    }

    private void send(String topic, String key, String eventType, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    /** Reads the topic from the start with a fresh group and returns the record with this key, or null. */
    private ConsumerRecord<String, String> findOn(String topic, String key, Duration within) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-reader-" + UUID.randomUUID(), "false", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                             .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
            return null;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
