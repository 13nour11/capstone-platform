package com.ecommerce.inventory.infrastructure.kafka;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * NFR-10 for the inventory Saga consumers: a failing record is retried, then parked on
 * {@code <topic>.DLT} with an ALERT log line, so a transient error never silently drops a
 * reservation or a release. Same design as payment-service.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    static final long RETRY_INTERVAL_MS = 1_000L;
    static final long MAX_RETRIES = 3L;
    private static final String DLT_SUFFIX = ".DLT";
    private static final int ANY_PARTITION = -1;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, ANY_PARTITION));

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("ALERT dead-letter: {} record key={} offset={} parked on {}{}: {}",
                            record.topic(), record.key(), record.offset(), record.topic(), DLT_SUFFIX,
                            exception.getMessage());
                    deadLetter.accept(record, exception);
                },
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
