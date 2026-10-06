package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.domain.event.OrderCancelled;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.event.PaymentFailed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InventoryKafkaListenerTest {

    @Mock
    private InventoryService inventoryService;

    private ObjectMapper objectMapper;
    private InventoryKafkaListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new InventoryKafkaListener(inventoryService, objectMapper);
    }

    @Test
    @DisplayName("Should invoke processOrderPlaced when OrderPlaced event is received")
    void shouldProcessOrderPlaced() {
        String payload = """
                {
                    "eventId": "evt-1",
                    "orderId": "ord-1",
                    "customerId": "cust-1",
                    "totalAmount": 100.00,
                    "items": [{"productId": 1, "quantity": 2, "unitPrice": 50.00}],
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0L, "ord-1", payload);
        record.headers().add(new RecordHeader("eventType", "OrderPlaced".getBytes(StandardCharsets.UTF_8)));

        listener.onOrderEvent(record);

        verify(inventoryService).processOrderPlaced(any(OrderPlaced.class));
    }

    @Test
    @DisplayName("Should invoke releaseReservation when OrderCancelled event is received")
    void shouldReleaseOnOrderCancelled() {
        String payload = """
                {
                    "eventId": "evt-2",
                    "orderId": "ord-2",
                    "reason": "USER_REQUEST",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0L, "ord-2", payload);
        record.headers().add(new RecordHeader("eventType", "OrderCancelled".getBytes(StandardCharsets.UTF_8)));

        listener.onOrderEvent(record);

        verify(inventoryService).releaseReservation(eq("evt-2"), eq("ord-2"), eq("USER_REQUEST"));
    }

    @Test
    @DisplayName("Should invoke releaseReservation when PaymentFailed event is received")
    void shouldReleaseOnPaymentFailed() {
        String payload = """
                {
                    "eventId": "evt-3",
                    "orderId": "ord-3",
                    "reason": "INSUFFICIENT_FUNDS",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ord-3", payload);
        record.headers().add(new RecordHeader("eventType", "PaymentFailed".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(record);

        verify(inventoryService).releaseReservation(eq("evt-3"), eq("ord-3"), eq("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("NFR-10: an unreadable payload is thrown as InvalidEventException (dead-letter topic, no retries)")
    void shouldThrowInvalidEvent_whenPayloadIsNotJson() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0L, "ord-9", "not-json");
        record.headers().add(new RecordHeader("eventType", "OrderPlaced".getBytes(StandardCharsets.UTF_8)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onOrderEvent(record))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("NFR-10: a processing failure propagates to the error handler (retry, then DLT)")
    void shouldPropagateFailure_whenServiceFails() {
        String payload = """
                {"eventId": "evt-10", "orderId": "ord-10", "reason": "X", "occurredAt": "2026-10-04T12:00:00Z"}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ord-10", payload);
        record.headers().add(new RecordHeader("eventType", "PaymentFailed".getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(inventoryService).releaseReservation("evt-10", "ord-10", "X");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onPaymentEvent(record))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should invoke confirmReservation when PaymentCompleted event is received")
    void shouldConfirmOnPaymentCompleted() {
        String payload = """
                {
                    "eventId": "evt-4",
                    "orderId": "ord-4",
                    "paymentId": "pay-4",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ord-4", payload);
        record.headers().add(new RecordHeader("eventType", "PaymentCompleted".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(record);

        verify(inventoryService).confirmReservation(eq("evt-4"), eq("ord-4"));
    }
}
