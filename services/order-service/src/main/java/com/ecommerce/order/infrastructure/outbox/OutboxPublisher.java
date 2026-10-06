package com.ecommerce.order.infrastructure.outbox;

import com.ecommerce.order.domain.OutboxEvent;
import com.ecommerce.order.domain.OutboxStatus;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
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

    @Value("${order.kafka.topics.order-events:order-events}")
    private String orderEventsTopic;

    @Value("${order.outbox.send-timeout-ms:5000}")
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

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:500}")
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
                        orderEventsTopic,
                        event.getAggregateId(),
                        event.getPayload()
                );

                if (event.getTraceparent() != null && !event.getTraceparent().isBlank()) {
                    record.headers().add(new RecordHeader("traceparent",
                            event.getTraceparent().getBytes(StandardCharsets.UTF_8)));
                }
                record.headers().add(new RecordHeader("eventType",
                        event.getEventType().getBytes(StandardCharsets.UTF_8)));

                // Continues the trace of the request that wrote the event, so one traceId spans
                // HTTP and Kafka instead of ending on this scheduler thread (NFR-06).
                Span span = continueTrace(event).name("outbox publish " + event.getEventType()).start();
                try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                    // Awaited: marking SENT on an unconfirmed send would drop the event if the
                    // broker rejected it.
                    kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                } finally {
                    span.end();
                }

                event.setStatus(OutboxStatus.SENT);
                outboxEventRepository.save(event);
                log.info("Published outbox event {} of type {} for order {}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (Exception ex) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), ex.getMessage());
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
