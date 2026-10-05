package com.ecommerce.payment.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records an event in the outbox table as part of the caller's transaction, so the event
 * exists if and only if the business change was committed.
 */
@Component
public class OutboxWriter {

    private static final String TRACEPARENT = "traceparent";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Tracer tracer,
                        Propagator propagator, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID eventId, String aggregateId, String topic, Object event) {
        repository.save(new OutboxEvent(eventId, aggregateId, topic, event.getClass().getSimpleName(),
                toJson(event), currentTraceparent(), clock.instant()));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise " + event.getClass().getSimpleName(), e);
        }
    }

    private String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
