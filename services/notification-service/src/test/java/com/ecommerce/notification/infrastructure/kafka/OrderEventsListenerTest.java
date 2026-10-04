package com.ecommerce.notification.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.ecommerce.notification.application.SendOrderNotificationService;
import com.ecommerce.notification.domain.OrderNotification;
import com.ecommerce.notification.domain.OrderNotification.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OrderEventsListenerTest {

    private final OrderEventsListener listener = new OrderEventsListener(mock(SendOrderNotificationService.class),
            new ObjectMapper().registerModule(new JavaTimeModule()), new SimpleMeterRegistry());

    @Test
    void shouldMapConfirmation_whenOrderConfirmedArrives() {
        String event = """
                {"eventId":"e1","eventType":"OrderConfirmed","eventVersion":1,"occurredAt":"2026-10-01T10:00:00Z",
                 "aggregateId":"ord-1","payload":{"orderId":"ord-1","customerId":"c-1","totalAmount":24.99}}""";

        assertThat(listener.toNotification(event))
                .contains(new OrderNotification("e1", Type.ORDER_CONFIRMED, "ord-1", "c-1", null));
    }

    @Test
    void shouldCarryReason_whenOrderCancelledArrives() {
        String event = """
                {"eventId":"e2","eventType":"OrderCancelled","aggregateId":"ord-2",
                 "payload":{"customerId":"c-2","reason":"PAYMENT_FAILED"}}""";

        assertThat(listener.toNotification(event))
                .contains(new OrderNotification("e2", Type.ORDER_CANCELLED, "ord-2", "c-2", "PAYMENT_FAILED"));
    }

    @Test
    void shouldSkip_whenEventTypeIsNotNotified() {
        String event = """
                {"eventId":"e3","eventType":"OrderPlaced","aggregateId":"ord-3","payload":{"customerId":"c-3"}}""";

        assertThat(listener.toNotification(event)).isEmpty();
    }

    @Test
    void shouldRejectAsPoison_whenJsonIsMalformed() {
        assertThatThrownBy(() -> listener.toNotification("{not json")).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void shouldRejectAsPoison_whenCustomerIdIsMissing() {
        String event = """
                {"eventId":"e4","eventType":"OrderConfirmed","aggregateId":"ord-4","payload":{}}""";

        assertThatThrownBy(() -> listener.toNotification(event)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void shouldRejectAsPoison_whenEventIdIsMissing() {
        assertThatThrownBy(() -> listener.toNotification("{\"eventType\":\"OrderConfirmed\"}"))
                .isInstanceOf(InvalidEventException.class);
    }
}
