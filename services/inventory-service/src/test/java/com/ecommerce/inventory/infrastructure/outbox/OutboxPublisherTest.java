package com.ecommerce.inventory.infrastructure.outbox;

import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> recordCaptor;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        OutboxTraceContext traceContext = new OutboxTraceContext(Tracer.NOOP, Propagator.NOOP);
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, traceContext);
        ReflectionTestUtils.setField(outboxPublisher, "inventoryEventsTopic", "inventory-events");
        ReflectionTestUtils.setField(outboxPublisher, "inventoryAlertsTopic", "inventory-alerts");
    }

    @Test
    @DisplayName("Should publish pending outbox events and mark SENT with eventType and eventId headers")
    void shouldPublishPendingEvents_andMarkAsSent() {
        OutboxEvent event = new OutboxEvent(
                "evt-inv-1",
                "Inventory",
                "ord-100",
                "InventoryReserved",
                "{\"orderId\":\"ord-100\"}",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                OutboxStatus.PENDING,
                Instant.now()
        );

        when(outboxEventRepository.findPendingEventsForUpdate(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, String> captured = recordCaptor.getValue();

        assertThat(captured.topic()).isEqualTo("inventory-events");
        assertThat(captured.key()).isEqualTo("ord-100");
        assertThat(new String(captured.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .isEqualTo("evt-inv-1");
        assertThat(new String(captured.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("InventoryReserved");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("At-least-once: a send the broker did not acknowledge stays PENDING and stops the batch")
    void shouldKeepEventPending_whenBrokerDoesNotAcknowledge() {
        OutboxEvent first = new OutboxEvent("evt-a", "Inventory", "ord-1", "InventoryReserved", "{}", null,
                OutboxStatus.PENDING, Instant.now());
        OutboxEvent second = new OutboxEvent("evt-b", "Inventory", "ord-1", "InventoryReleased", "{}", null,
                OutboxStatus.PENDING, Instant.now());
        when(outboxEventRepository.findPendingEventsForUpdate(50)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        outboxPublisher.publishPendingEvents();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("B4: LowStock goes to inventory-alerts, not to the Saga topic")
    void shouldRouteLowStockToAlertsTopic() {
        OutboxEvent alert = new OutboxEvent("evt-low", "Stock", "4", "LowStock", "{}", null,
                OutboxStatus.PENDING, Instant.now());
        when(outboxEventRepository.findPendingEventsForUpdate(50)).thenReturn(List.of(alert));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().topic()).isEqualTo("inventory-alerts");
    }

    @Test
    @DisplayName("Should do nothing when no pending events exist")
    void shouldDoNothing_whenNoPendingEvents() {
        when(outboxEventRepository.findPendingEventsForUpdate(50)).thenReturn(List.of());

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxEventRepository, never()).save(any());
    }
}
