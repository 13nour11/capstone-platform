package com.ecommerce.order.infrastructure.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * NFR-10: a failing record is retried, then parked on {@code <topic>.DLT} so the partition keeps moving.
 * Records that can never succeed skip the retries. (Same policy as payment-service.)
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 3L;
    private static final String DLT_SUFFIX = ".DLT";
    private static final int ANY_PARTITION = -1;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, ANY_PARTITION)),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
