package com.ecommerce.order.infrastructure.outbox;

import com.ecommerce.order.domain.OutboxEvent;
import com.ecommerce.order.domain.OutboxStatus;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
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
 * Relays committed outbox rows to {@code order-events}, at least once (NFR-10). A row is marked SENT only after
 * the broker acknowledged it; on the first failure the batch stops, so a later event of the same order never
 * overtakes an earlier one. The send continues the trace stored with the row (NFR-06).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxTraceContext traceContext;

    @Value("${order.kafka.topics.order-events:order-events}")
    private String orderEventsTopic;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxTraceContext traceContext) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.traceContext = traceContext;
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:500}")
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
                log.warn("Outbox event {} ({}) not published, will retry: {}",
                        event.getId(), event.getEventType(), ex.getMessage());
                return;
            }
            event.setStatus(OutboxStatus.SENT);
            outboxEventRepository.save(event);
            log.info("Published outbox event {} of type {} for order {}",
                    event.getId(), event.getEventType(), event.getAggregateId());
        }
    }

    private void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(orderEventsTopic, event.getAggregateId(), event.getPayload());
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
