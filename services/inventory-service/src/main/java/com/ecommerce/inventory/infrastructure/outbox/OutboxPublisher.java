package com.ecommerce.inventory.infrastructure.outbox;

import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka, at least once (NFR-10). A row is marked SENT only after the broker
 * acknowledged it; on the first failure the batch stops, so later events of the same order never overtake it.
 */
@Component
public class OutboxPublisher {

    static final String LOW_STOCK = "LowStock";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxTraceContext traceContext;

    @Value("${inventory.kafka.topics.inventory-events:inventory-events}")
    private String inventoryEventsTopic;

    @Value("${inventory.kafka.topics.inventory-alerts:inventory-alerts}")
    private String inventoryAlertsTopic;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxTraceContext traceContext) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.traceContext = traceContext;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForUpdate(50);
        for (OutboxEvent event : events) {
            try {
                send(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Inventory outbox event {} ({}) not published, will retry: {}",
                        event.getId(), event.getEventType(), ex.getMessage());
                return;
            }
            event.setStatus(OutboxStatus.SENT);
            outboxEventRepository.save(event);
            log.info("Published inventory outbox event {} of type {} for aggregate {}",
                    event.getId(), event.getEventType(), event.getAggregateId());
        }
    }

    private void send(OutboxEvent event) throws Exception {
        // B4 alerts have their own topic; every Saga event goes to inventory-events
        String topic = LOW_STOCK.equals(event.getEventType()) ? inventoryAlertsTopic : inventoryEventsTopic;
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
        record.headers().add(new RecordHeader("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("eventId", event.getId().getBytes(StandardCharsets.UTF_8)));

        Span span = traceContext.continueTrace(event.getTraceparent(), "outbox publish " + event.getEventType());
        try (Tracer.SpanInScope ignored = traceContext.tracer().withSpan(span)) {
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            span.end();
        }
    }
}
