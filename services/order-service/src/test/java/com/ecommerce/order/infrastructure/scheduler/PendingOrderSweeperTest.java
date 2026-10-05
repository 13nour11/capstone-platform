package com.ecommerce.order.infrastructure.scheduler;

import com.ecommerce.order.application.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingOrderSweeperTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PendingOrderSweeper sweeper;

    @Test
    @DisplayName("F10: cancels orders stuck in PENDING past the configured timeout")
    void shouldCancelOrdersPastTheTimeout() {
        ReflectionTestUtils.setField(sweeper, "pendingTimeoutMinutes", 10);
        when(orderService.cancelOrdersPendingLongerThan(Duration.ofMinutes(10))).thenReturn(2);

        sweeper.cancelStuckOrders();

        verify(orderService).cancelOrdersPendingLongerThan(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("A failing sweep is logged, not propagated, so the scheduler keeps running")
    void shouldSwallowFailuresSoTheScheduleSurvives() {
        ReflectionTestUtils.setField(sweeper, "pendingTimeoutMinutes", 10);
        when(orderService.cancelOrdersPendingLongerThan(any(Duration.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        sweeper.cancelStuckOrders();

        verify(orderService).cancelOrdersPendingLongerThan(Duration.ofMinutes(10));
    }
}
