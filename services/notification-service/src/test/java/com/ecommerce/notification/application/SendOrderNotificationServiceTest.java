package com.ecommerce.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.ecommerce.notification.domain.OrderNotification;
import com.ecommerce.notification.domain.OrderNotification.Type;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SendOrderNotificationServiceTest {

    private static final OrderNotification CONFIRMED =
            new OrderNotification("evt-1", Type.ORDER_CONFIRMED, "ord-1", "cust-1", null);

    private final NotificationSender sender = mock(NotificationSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SendOrderNotificationService service =
            new SendOrderNotificationService(sender, new RecentEventIds(), meterRegistry);

    @Test
    void shouldSendOnce_whenSameEventIsDeliveredTwice() {
        service.notifyCustomer(CONFIRMED);
        service.notifyCustomer(CONFIRMED);

        verify(sender, times(1)).send(CONFIRMED);
        assertThat(meterRegistry.counter("notifications.sent", "type", "ORDER_CONFIRMED").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("notifications.duplicates").count()).isEqualTo(1);
    }

    @Test
    void shouldSendAgain_whenPreviousAttemptFailed() {
        willThrow(new IllegalStateException("smtp down")).willDoNothing().given(sender).send(CONFIRMED);

        assertThatThrownBy(() -> service.notifyCustomer(CONFIRMED)).isInstanceOf(IllegalStateException.class);
        service.notifyCustomer(CONFIRMED);

        verify(sender, times(2)).send(CONFIRMED);
    }

    @Test
    void shouldForgetOldestIds_whenCapacityIsExceeded() {
        RecentEventIds ids = new RecentEventIds();

        for (int i = 0; i <= RecentEventIds.CAPACITY; i++) {
            ids.add("evt-" + i);
        }

        assertThat(ids.contains("evt-0")).isFalse();
        assertThat(ids.contains("evt-" + RecentEventIds.CAPACITY)).isTrue();
    }
}
