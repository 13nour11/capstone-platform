package com.ecommerce.payment.infrastructure.outbox;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate,
            TransactionOperations.withoutTransaction(), new OutboxProperties(50, Duration.ofSeconds(1)),
            Tracer.NOOP, Propagator.NOOP, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldPublishWithRoutingHeaders_whenEventIsPending() {
        OutboxEvent event = event("order-1");
        when(repository.lockNextBatch(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acknowledged());

        int published = publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();
        assertThat(published).isEqualTo(1);
        assertThat(record.topic()).isEqualTo("payment-events");
        assertThat(record.key()).isEqualTo("order-1");
        assertThat(header(record, OutboxHeaders.EVENT_TYPE)).isEqualTo("PaymentCompleted");
        assertThat(header(record, OutboxHeaders.EVENT_ID)).isEqualTo(event.getId().toString());
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStopBatchAndKeepEventPending_whenBrokerFails() {
        OutboxEvent first = event("order-1");
        OutboxEvent second = event("order-1");
        when(repository.lockNextBatch(50)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int published = publisher.publishPending();

        assertThat(published).isZero();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");
        assertThat(second.getAttempts()).isZero();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    private static OutboxEvent event(String orderId) {
        return new OutboxEvent(UUID.randomUUID(), orderId, "payment-events", "PaymentCompleted",
                "{\"orderId\":\"" + orderId + "\"}", null, NOW);
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
