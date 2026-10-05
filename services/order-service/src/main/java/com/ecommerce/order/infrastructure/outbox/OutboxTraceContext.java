package com.ecommerce.order.infrastructure.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * NFR-06 across the outbox: the writer stores the W3C {@code traceparent} of the transaction that created the
 * event; the publisher continues that trace when it sends the row, so one traceId spans HTTP and Kafka.
 */
@Component
public class OutboxTraceContext {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current trace as a {@code traceparent} value, or null outside a trace. */
    public String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** A span that continues the stored trace (a new trace when none was stored). */
    Span continueTrace(String traceparent, String name) {
        Span.Builder builder = traceparent == null
                ? tracer.spanBuilder().setNoParent()
                : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
        return builder.name(name).start();
    }

    Tracer tracer() {
        return tracer;
    }
}
