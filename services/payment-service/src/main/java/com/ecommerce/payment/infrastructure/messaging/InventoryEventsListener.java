package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.application.HandleInventoryReservedService;
import com.ecommerce.payment.domain.event.InventoryReserved;
import com.ecommerce.payment.domain.event.Topics;
import com.ecommerce.payment.infrastructure.outbox.OutboxHeaders;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Payment's only trigger in the Saga: {@code InventoryReserved}. Other inventory events are not ours. */
@Component
public class InventoryEventsListener {

    static final String INVENTORY_RESERVED = "InventoryReserved";

    private final HandleInventoryReservedService handler;
    private final ObjectMapper objectMapper;

    public InventoryEventsListener(HandleInventoryReservedService handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "${spring.application.name}")
    public void onInventoryEvent(ConsumerRecord<String, String> record) {
        if (!INVENTORY_RESERVED.equals(eventType(record))) {
            return;
        }
        handler.handle(parse(record));
    }

    private InventoryReserved parse(ConsumerRecord<String, String> record) {
        try {
            InventoryReserved event = objectMapper.readValue(record.value(), InventoryReserved.class);
            if (event == null || !event.isComplete()) {
                throw new InvalidEventException("InventoryReserved needs eventId, orderId and a positive totalAmount: "
                        + record.value());
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Unreadable InventoryReserved payload", e);
        }
    }

    private static String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(OutboxHeaders.EVENT_TYPE);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
