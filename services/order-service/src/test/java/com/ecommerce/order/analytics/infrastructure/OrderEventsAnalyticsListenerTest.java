package com.ecommerce.order.analytics.infrastructure;

import com.ecommerce.order.analytics.application.OrderAnalyticsProjector;
import com.ecommerce.order.analytics.domain.AnalyticsOrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventsAnalyticsListenerTest {

    private final OrderAnalyticsProjector projector = mock(OrderAnalyticsProjector.class);
    private final OrderEventsAnalyticsListener listener =
            new OrderEventsAnalyticsListener(projector, new ObjectMapper());

    @Test
    void shouldProjectPlacedOrder_whenOrderPlacedArrives() {
        listener.onOrderEvent(record("OrderPlaced",
                "{\"eventId\":\"e1\",\"orderId\":\"o1\",\"totalAmount\":12.50,\"occurredAt\":\"2026-10-04T10:00:00Z\"}"));

        verify(projector).onOrderPlaced(eq("e1"), eq("o1"), amountOf("12.50"), eq(Instant.parse("2026-10-04T10:00:00Z")));
    }

    @Test
    void shouldUseBrokerTimestamp_whenOccurredAtIsMissing() {
        listener.onOrderEvent(record("OrderPlaced", "{\"eventId\":\"e1\",\"orderId\":\"o1\",\"totalAmount\":5}"));

        verify(projector).onOrderPlaced(eq("e1"), eq("o1"), amountOf("5"), eq(Instant.ofEpochMilli(1_000L)));
    }

    @Test
    void shouldCloseOrder_whenOrderIsConfirmedOrCancelled() {
        listener.onOrderEvent(record("OrderConfirmed", "{\"eventId\":\"e2\",\"orderId\":\"o1\"}"));
        listener.onOrderEvent(record("OrderCancelled", "{\"eventId\":\"e3\",\"orderId\":\"o2\"}"));

        verify(projector).onOrderClosed("e2", "o1", AnalyticsOrderStatus.CONFIRMED);
        verify(projector).onOrderClosed("e3", "o2", AnalyticsOrderStatus.CANCELLED);
    }

    @Test
    void shouldIgnoreEvent_whenTypeIsUnknownOrMissing() {
        listener.onOrderEvent(record("OrderShipped", "{}"));
        listener.onOrderEvent(record(null, "{}"));

        verifyNoInteractions(projector);
    }

    @Test
    void shouldRejectEvent_whenPayloadIsUnusable() {
        assertThatThrownBy(() -> listener.onOrderEvent(record("OrderPlaced", "not-json")))
                .isInstanceOf(InvalidOrderEventException.class);
        assertThatThrownBy(() -> listener.onOrderEvent(record("OrderPlaced", "{\"eventId\":\"e1\",\"orderId\":\"o1\"}")))
                .isInstanceOf(InvalidOrderEventException.class);
        assertThatThrownBy(() -> listener.onOrderEvent(record("OrderConfirmed", "{\"orderId\":\"o1\"}")))
                .isInstanceOf(InvalidOrderEventException.class);
        verifyNoInteractions(projector);
    }

    /** Money is compared by value: 12.5 and 12.50 are the same amount. */
    private static BigDecimal amountOf(String expected) {
        return argThat(actual -> actual != null && actual.compareTo(new BigDecimal(expected)) == 0);
    }

    private static ConsumerRecord<String, String> record(String eventType, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0L, 1_000L,
                TimestampType.CREATE_TIME, 0, 0, "o1", payload,
                new RecordHeaders(), Optional.empty());
        if (eventType != null) {
            record.headers().add(OrderEventsAnalyticsListener.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
