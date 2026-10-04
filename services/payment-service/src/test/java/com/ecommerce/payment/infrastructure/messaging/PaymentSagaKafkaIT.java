package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.domain.event.Topics;
import com.ecommerce.payment.infrastructure.outbox.OutboxHeaders;
import com.ecommerce.payment.infrastructure.outbox.OutboxPublisher;
import com.ecommerce.payment.support.PostgresTestcontainersConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The full payment hop of the Saga over a real broker: InventoryReserved in, PaymentCompleted out,
 * a redelivered event ignored, and an unreadable event parked on the dead-letter topic.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
@EmbeddedKafka(partitions = 1,
        topics = {Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS, Topics.INVENTORY_EVENTS + ".DLT"})
class PaymentSagaKafkaIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void shouldPublishPaymentCompletedOnce_whenInventoryReservedIsDeliveredTwice() {
        String orderId = "order-" + UUID.randomUUID();
        String payload = "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"totalAmount\":99.90,\"occurredAt\":\"2026-10-04T10:00:00Z\"}"
                .formatted(UUID.randomUUID(), orderId);

        send(Topics.INVENTORY_EVENTS, orderId, "InventoryReserved", payload);
        send(Topics.INVENTORY_EVENTS, orderId, "InventoryReserved", payload);
        awaitConsumedUpTo(sendMarker());
        outboxPublisher.publishPending();

        ConsumerRecord<String, String> record = recordFor(Topics.PAYMENT_EVENTS, orderId);
        assertThat(header(record, OutboxHeaders.EVENT_TYPE)).isEqualTo("PaymentCompleted");
        assertThat(record.value()).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void shouldIgnoreOtherInventoryEvents() {
        String orderId = "order-" + UUID.randomUUID();

        send(Topics.INVENTORY_EVENTS, orderId, "InventoryReleased", "{\"orderId\":\"%s\"}".formatted(orderId));
        awaitConsumedUpTo(sendMarker());

        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isZero();
    }

    @Test
    void shouldParkUnreadableEventOnDeadLetterTopic() {
        String orderId = "order-" + UUID.randomUUID();

        send(Topics.INVENTORY_EVENTS, orderId, "InventoryReserved", "not-json");

        ConsumerRecord<String, String> record = recordFor(Topics.INVENTORY_EVENTS + ".DLT", orderId);
        assertThat(record.value()).isEqualTo("not-json");
    }

    /** Sends a valid InventoryReserved; once its payment exists, every earlier record on the partition was consumed. */
    private String sendMarker() {
        String marker = "marker-" + UUID.randomUUID();
        send(Topics.INVENTORY_EVENTS, marker, "InventoryReserved",
                "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"totalAmount\":1}".formatted(UUID.randomUUID(), marker));
        return marker;
    }

    private void awaitConsumedUpTo(String marker) {
        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM payments WHERE order_id = ?", marker) == 1);
    }

    private ConsumerRecord<String, String> recordFor(String topic, String key) {
        try (Consumer<String, String> consumer = consumer(topic)) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }

    private void send(String topic, String key, String eventType, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(OutboxHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verify-" + UUID.randomUUID(), "false", broker);
        props.put("auto.offset.reset", "earliest");
        Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }

    private long count(String sql, String param) {
        return jdbcClient.sql(sql).param(param).query(Long.class).single();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
