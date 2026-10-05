package com.ecommerce.inventory.infrastructure.outbox;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
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
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, Tracer.NOOP, Propagator.NOOP);
        ReflectionTestUtils.setField(outboxPublisher, "inventoryEventsTopic", "inventory-events");
    }

    @Test
    @DisplayName("Should publish pending outbox events and mark SENT with traceparent and eventType headers")
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
        assertThat(new String(captured.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(new String(captured.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("InventoryReserved");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(outboxEventRepository).save(event);
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
