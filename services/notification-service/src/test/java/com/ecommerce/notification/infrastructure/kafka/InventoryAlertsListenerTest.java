package com.ecommerce.notification.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ecommerce.notification.application.AlertBroadcaster;
import com.ecommerce.notification.domain.LowStockAlert;
import com.fasterxml.jackson.databind.ObjectMapper;

class InventoryAlertsListenerTest {

    private final AlertBroadcaster broadcaster = mock(AlertBroadcaster.class);
    private final InventoryAlertsListener listener = new InventoryAlertsListener(broadcaster, new ObjectMapper());

    @Test
    void shouldBroadcast_whenLowStockArrives() {
        listener.onAlert("""
                {"eventId":"e-1","productId":4,"available":2,"threshold":5,"occurredAt":"2026-10-01T10:00:00Z"}""",
                "LowStock");

        verify(broadcaster).broadcast(new LowStockAlert("e-1", 4L, 2, 5, Instant.parse("2026-10-01T10:00:00Z")));
    }

    @Test
    void shouldIgnoreOtherEventTypes() {
        listener.onAlert("{}", "SomethingElse");

        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void shouldRejectAsPoison_whenProductIdIsMissing() {
        assertThatThrownBy(() -> listener.onAlert("{\"eventId\":\"e-2\"}", "LowStock"))
                .isInstanceOf(InvalidEventException.class);
    }
}
