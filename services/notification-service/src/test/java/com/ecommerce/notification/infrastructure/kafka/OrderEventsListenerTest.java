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

/** The order-service contract: flat JSON payload, event type in the {@code eventType} header. */
class OrderEventsListenerTest {

    private final OrderEventsListener listener = new OrderEventsListener(mock(SendOrderNotificationService.class),
            new ObjectMapper().registerModule(new JavaTimeModule()), new SimpleMeterRegistry());

    @Test
    void shouldMapConfirmation_whenOrderConfirmedArrives() {
        String event = """
                {"eventId":"e1","orderId":"ord-1","customerId":"c-1","occurredAt":"2026-10-01T10:00:00Z"}""";

        assertThat(listener.toNotification("OrderConfirmed", event))
                .contains(new OrderNotification("e1", Type.ORDER_CONFIRMED, "ord-1", "c-1", null));
    }

    @Test
    void shouldCarryReason_whenOrderCancelledArrives() {
        String event = """
                {"eventId":"e2","orderId":"ord-2","customerId":"c-2","reason":"PAYMENT_FAILED"}""";

        assertThat(listener.toNotification("OrderCancelled", event))
                .contains(new OrderNotification("e2", Type.ORDER_CANCELLED, "ord-2", "c-2", "PAYMENT_FAILED"));
    }

    @Test
    void shouldSkip_whenEventTypeIsNotNotified() {
        String event = """
                {"eventId":"e3","orderId":"ord-3","customerId":"c-3"}""";

        assertThat(listener.toNotification("OrderPlaced", event)).isEmpty();
    }

    @Test
    void shouldSkip_whenEventTypeHeaderIsMissing() {
        assertThat(listener.toNotification(null, "{\"eventId\":\"e5\"}")).isEmpty();
    }

    @Test
    void shouldRejectAsPoison_whenJsonIsMalformed() {
        assertThatThrownBy(() -> listener.toNotification("OrderConfirmed", "{not json"))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void shouldRejectAsPoison_whenCustomerIdIsMissing() {
        String event = """
                {"eventId":"e4","orderId":"ord-4"}""";

        assertThatThrownBy(() -> listener.toNotification("OrderConfirmed", event))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void shouldRejectAsPoison_whenEventIdIsMissing() {
        assertThatThrownBy(() -> listener.toNotification("OrderConfirmed", "{\"orderId\":\"ord-6\",\"customerId\":\"c\"}"))
                .isInstanceOf(InvalidEventException.class);
    }
}
