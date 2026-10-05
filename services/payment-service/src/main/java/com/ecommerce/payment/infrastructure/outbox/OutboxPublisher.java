package com.ecommerce.payment.infrastructure.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka (at-least-once).
 *
 * <p>A row is marked published only after the broker acknowledged it, inside the transaction
 * that holds its row lock. A crash between the acknowledgement and the commit re-sends the
 * event, which consumers absorb through their {@code processed_event} check.
 * On the first failure the batch stops, so later events of the same order never overtake it.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TRACEPARENT = "traceparent";

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionOperations transactions;
    private final OutboxProperties properties;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                           TransactionOperations transactions, OutboxProperties properties,
                           Tracer tracer, Propagator propagator, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactions = transactions;
        this.properties = properties;
        this.tracer = tracer;
        this.propagator = propagator;
        this.clock = clock;
    }

    /**
     * @return the number of events published in this poll
     */
    public int publishPending() {
        Integer published = transactions.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(properties.batchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished(clock.instant());
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("Interrupted while publishing");
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                event.recordFailure(e.getMessage());
                log.warn("Outbox event {} ({}) not published, will retry: {}",
                        event.getId(), event.getEventType(), e.getMessage());
                break;
            }
        }
        return published;
    }

    private void send(OutboxEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getPayload());
        record.headers()
                .add(OutboxHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8))
                .add(OutboxHeaders.EVENT_ID, event.getId().toString().getBytes(StandardCharsets.UTF_8));

        Span span = continueTrace(event).name("outbox publish " + event.getEventType()).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            span.end();
        }
    }

    /** Continues the trace of the request that wrote the event, so one traceId spans HTTP and Kafka. */
    private Span.Builder continueTrace(OutboxEvent event) {
        if (event.getTraceparent() == null) {
            return tracer.spanBuilder().setNoParent();
        }
        return propagator.extract(Map.of(TRACEPARENT, event.getTraceparent()), Map::get);
    }
}
