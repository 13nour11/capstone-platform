package com.ecommerce.review.infrastructure.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OutboxMetrics {

    /** Unpublished events. A growing value means Kafka is unreachable (failure mode: broker down). */
    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, OutboxEventRepository repository) {
        return Gauge.builder("outbox.pending", repository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }
}
