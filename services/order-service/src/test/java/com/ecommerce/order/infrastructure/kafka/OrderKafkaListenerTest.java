package com.ecommerce.order.infrastructure.kafka;

import com.ecommerce.order.application.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderKafkaListenerTest {

    @Mock
    private OrderService orderService;

    private ObjectMapper objectMapper;
    private OrderKafkaListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new OrderKafkaListener(orderService, objectMapper);
    }

    @Test
    @DisplayName("Saga: Should invoke confirmOrder when PaymentCompleted is received")
    void shouldConfirmOrder_onPaymentCompleted() {
        String payload = """
                {
                    "eventId": "evt-pay-1",
                    "orderId": "ord-100",
                    "paymentId": "pay-100",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ord-100", payload);
        record.headers().add(new RecordHeader("eventType", "PaymentCompleted".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(record);

        verify(orderService).confirmOrder("evt-pay-1", "ord-100");
    }

    @Test
    @DisplayName("Saga: Should invoke cancelOrder when PaymentFailed is received")
    void shouldCancelOrder_onPaymentFailed() {
        String payload = """
                {
                    "eventId": "evt-pay-2",
                    "orderId": "ord-200",
                    "reason": "CARD_DECLINED",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ord-200", payload);
        record.headers().add(new RecordHeader("eventType", "PaymentFailed".getBytes(StandardCharsets.UTF_8)));

        listener.onPaymentEvent(record);

        verify(orderService).cancelOrder("evt-pay-2", "ord-200", "CARD_DECLINED");
    }

    @Test
    @DisplayName("Saga: Should invoke cancelOrder when InventoryReservationFailed is received")
    void shouldCancelOrder_onInventoryReservationFailed() {
        String payload = """
                {
                    "eventId": "evt-inv-3",
                    "orderId": "ord-300",
                    "reason": "OUT_OF_STOCK",
                    "occurredAt": "2026-10-04T12:00:00Z"
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("inventory-events", 0, 0L, "ord-300", payload);
        record.headers().add(new RecordHeader("eventType", "InventoryReservationFailed".getBytes(StandardCharsets.UTF_8)));

        listener.onInventoryEvent(record);

        verify(orderService).cancelOrder("evt-inv-3", "ord-300", "OUT_OF_STOCK");
    }
}
