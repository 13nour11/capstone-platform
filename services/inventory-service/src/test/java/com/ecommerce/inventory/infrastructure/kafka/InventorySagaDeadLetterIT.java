package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.domain.event.OrderPlaced;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * NFR-10 over a real broker: the inventory Saga consumers retry a failing record, then park it on
 * {@code <topic>.DLT}; an unreadable record goes there without retries.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {"order-events", "order-events.DLT", "payment-events", "payment-events.DLT", "inventory-events"})
class InventorySagaDeadLetterIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ATTEMPTS = 1 + (int) KafkaErrorHandlingConfig.MAX_RETRIES;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    @DisplayName("A transient failure while reserving is retried and succeeds; nothing reaches the DLT")
    void shouldRetryTransientFailure_thenSucceed() {
        String orderId = "ord-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            OrderPlaced event = invocation.getArgument(0);
            if (event.orderId().equals(orderId) && calls.incrementAndGet() < 3) {
                throw new IllegalStateException("database briefly unavailable");
            }
            return null;
        }).when(inventoryService).processOrderPlaced(any(OrderPlaced.class));

        send("order-events", orderId, "OrderPlaced", orderPlaced(orderId));

        verify(inventoryService, timeout(TIMEOUT.toMillis()).times(3)).processOrderPlaced(any(OrderPlaced.class));
        assertThat(findOn("order-events.DLT", orderId, Duration.ofSeconds(3))).isNull();
    }

    @Test
    @DisplayName("A release that keeps failing is retried, then parked on payment-events.DLT")
    void shouldParkOnDlt_whenRetriesAreExhausted() {
        String orderId = "ord-" + UUID.randomUUID();
        doThrow(new IllegalStateException("database down"))
                .when(inventoryService).releaseReservation(anyString(), eq(orderId), anyString());

        send("payment-events", orderId, "PaymentFailed",
                "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"reason\":\"CARD_DECLINED\",\"occurredAt\":\"%s\"}"
                        .formatted(UUID.randomUUID(), orderId, Instant.now()));

        ConsumerRecord<String, String> parked = findOn("payment-events.DLT", orderId, TIMEOUT);
        assertThat(parked).isNotNull();
        assertThat(header(parked, "eventType")).isEqualTo("PaymentFailed");
        verify(inventoryService, times(ATTEMPTS)).releaseReservation(anyString(), eq(orderId), anyString());
    }

    @Test
    @DisplayName("An unreadable order event is parked on order-events.DLT and never reaches the service")
    void shouldParkUnreadableEvent_withoutCallingTheService() {
        String orderId = "ord-" + UUID.randomUUID();

        send("order-events", orderId, "OrderCancelled", "{not json");

        assertThat(findOn("order-events.DLT", orderId, TIMEOUT)).isNotNull();
        verify(inventoryService, never()).releaseReservation(anyString(), eq(orderId), anyString());
    }

    private static String orderPlaced(String orderId) {
        return ("{\"eventId\":\"%s\",\"orderId\":\"%s\",\"customerId\":\"c-1\",\"totalAmount\":10.00,"
                + "\"items\":[{\"productId\":1,\"quantity\":1,\"unitPrice\":10.00}],\"occurredAt\":\"%s\"}")
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
