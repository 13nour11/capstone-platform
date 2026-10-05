package com.ecommerce.notification.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;

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

    @Test
    void shouldNotifyFromTheFlatEventTheProducersActuallyPublish() {
        // order-service publishes the event's own fields at the top level and puts the type in the
        // eventType Kafka header. Requiring a nested "payload" sent every order event to the DLT.
        String event = """
                {"eventId":"e5","orderId":"ord-5","customerId":"c-5",
                 "occurredAt":"2026-10-05T22:07:17Z"}""";

        Optional<OrderNotification> notification = listener.toNotification(event, "OrderConfirmed");

        assertThat(notification).isPresent();
        assertThat(notification.get().orderId()).isEqualTo("ord-5");
        assertThat(notification.get().customerId()).isEqualTo("c-5");
        assertThat(notification.get().type()).isEqualTo(Type.ORDER_CONFIRMED);
    }

    @Test
    void shouldReadTheCancelReasonFromAFlatEvent() {
        String event = """
                {"eventId":"e6","orderId":"ord-6","customerId":"c-6","reason":"PAYMENT_FAILED"}""";

        Optional<OrderNotification> notification = listener.toNotification(event, "OrderCancelled");

        assertThat(notification).isPresent();
        assertThat(notification.get().reason()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void shouldSkipAnEventTypeItDoesNotNotifyOn_fromTheHeader() {
        String event = """
                {"eventId":"e7","orderId":"ord-7","customerId":"c-7"}""";

        assertThat(listener.toNotification(event, "OrderPlaced")).isEmpty();
    }

    @Test
    void shouldPreferTheHeaderTypeOverTheBody() {
        String event = """
                {"eventId":"e8","eventType":"OrderPlaced","orderId":"ord-8","customerId":"c-8"}""";

        assertThat(listener.toNotification(event, "OrderConfirmed")).isPresent();
    }
}
