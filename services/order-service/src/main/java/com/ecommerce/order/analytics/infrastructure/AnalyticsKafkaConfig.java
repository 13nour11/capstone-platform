package com.ecommerce.order.analytics.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka wiring owned by the analytics module. It reads plain JSON strings and declares no
 * ConsumerFactory, ProducerFactory or KafkaTemplate bean, so the order-service's own
 * auto-configured Kafka beans stay untouched.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsKafkaConfig implements DisposableBean {

    public static final String CONTAINER_FACTORY = "analyticsKafkaListenerContainerFactory";
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 3L;
    private static final String DLT_SUFFIX = ".DLT";
    private static final int ANY_PARTITION = -1;

    private final DefaultKafkaProducerFactory<String, String> deadLetterProducerFactory;
    private final KafkaProperties kafkaProperties;

    public AnalyticsKafkaConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties(null);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        this.deadLetterProducerFactory = new DefaultKafkaProducerFactory<>(producerProps);
    }

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> analyticsKafkaListenerContainerFactory() {
        Map<String, Object> consumerProps = kafkaProperties.buildConsumerProperties(null);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // A new projection must read the topic from the start to see every order.
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(new KafkaTemplate<>(deadLetterProducerFactory),
                        (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, ANY_PARTITION)),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        errorHandler.addNotRetryableExceptions(InvalidOrderEventException.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProps));
        factory.setCommonErrorHandler(errorHandler);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Override
    public void destroy() {
        deadLetterProducerFactory.destroy();
    }
}
