package com.ecommerce.inventory.infrastructure.outbox;

import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Tracer tracer;
    private final Propagator propagator;

    @Value("${inventory.kafka.topics.inventory-events:inventory-events}")
    private String inventoryEventsTopic;

    @Value("${inventory.outbox.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           Tracer tracer,
                           Propagator propagator) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events;
        try {
            events = outboxEventRepository.findPendingEventsForUpdate(50);
        } catch (Exception e) {
            events = outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        }

        if (events == null || events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        inventoryEventsTopic,
                        event.getAggregateId(),
                        event.getPayload()
                );

                if (event.getTraceparent() != null && !event.getTraceparent().isBlank()) {
                    record.headers().add(new RecordHeader("traceparent",
                            event.getTraceparent().getBytes(StandardCharsets.UTF_8)));
                }
                record.headers().add(new RecordHeader("eventType",
                        event.getEventType().getBytes(StandardCharsets.UTF_8)));

                // Continues the trace of the consume that wrote the event (NFR-06), and awaits the
                // send so an unconfirmed publish is never marked SENT.
                Span span = continueTrace(event).name("outbox publish " + event.getEventType()).start();
                try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                    kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                } finally {
                    span.end();
                }

                event.setStatus(OutboxStatus.SENT);
                outboxEventRepository.save(event);
                log.info("Published inventory outbox event {} of type {} for aggregate {}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (Exception ex) {
                log.error("Failed to publish inventory outbox event {}: {}", event.getId(), ex.getMessage());
            }
        }
    }

    private Span.Builder continueTrace(OutboxEvent event) {
        if (event.getTraceparent() == null || event.getTraceparent().isBlank()) {
            return tracer.spanBuilder().setNoParent();
        }
        return propagator.extract(Map.of("traceparent", event.getTraceparent()), Map::get);
    }
}
