package com.ecommerce.inventory.infrastructure.outbox;

import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
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

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${inventory.kafka.topics.inventory-events:inventory-events}")
    private String inventoryEventsTopic;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
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

                // Synchronously wait for Kafka broker ACK to guarantee zero message loss
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.SENT);
                outboxEventRepository.save(event);
                log.info("Published inventory outbox event {} of type {} for aggregate {}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (Exception ex) {
                log.error("Failed to publish inventory outbox event {}: {}", event.getId(), ex.getMessage());
                // Halt the batch on failure to preserve sequential ordering and avoid message skipping
                break;
            }
        }
    }
}

